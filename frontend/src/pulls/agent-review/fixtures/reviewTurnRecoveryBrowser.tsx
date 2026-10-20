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
import { useCallback, useState } from 'react';
import { flushSync } from 'react-dom';
import { createFixtureRoot } from '../../../fixtures/reactFixture';
import type { AgentHistoryPageDto, AgentStreamMessage, AgentTurnDto, Bridge } from '../../../types';
import { CHECKING_ACCEPTANCE, UNCONFIRMED_REQUEST } from '../../../agents/contracts';
import { parseAgentEventSequence, type AgentEventEnvelope } from '../../../agents/events';
import contracts from '../../../agents/fixtures/agent-contracts-v1.json';
import eventFixture from '../../../agents/fixtures/agent-events-v2.json';
import { assert, changeInput, settle, until } from '../../../fixtures/harness';
import AgentReviewTab from '../AgentReviewTab';
import type { AgentReviewDto, ReviewMessageDto } from '../contracts';
import { reviewFixture, runningReviewFixture } from './review';

export async function run(): Promise<{ name: string; error?: string }[]> {
  const previous = window.bridge;
  const { host, root, dispose } = createFixtureRoot();
  const results: { name: string; error?: string }[] = [];
  let replaceReview = (_review: AgentReviewDto): void => {};
  let changedReviews: AgentReviewDto[] = [];
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
  const mount = async (initial: AgentReviewDto, saved: () => AgentReviewDto, message: Bridge['messageAgentReview'],
    overrides: Partial<Pick<Bridge, 'getAgentReview' | 'getAgentTurn' | 'readAgentReviewHistory' | 'subscribeAgentReview' | 'resumeAgentReview'>> = {}) => {
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
      ...overrides,
    } satisfies Pick<Bridge, 'getModelSettings' | 'getAgentReview' | 'getAgentTurn' | 'readAgentReviewHistory'
      | 'readAgentReviewApprovals' | 'subscribeAgentReview' | 'messageAgentReview'>;
    Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
    changedReviews = [];
    function Surface() {
      const [review, setReview] = useState(initial);
      replaceReview = setReview;
      const change = useCallback((updated: AgentReviewDto) => { changedReviews.push(updated); setReview(updated); }, []);
      return <AgentReviewTab workspaceId={review.workspaceId} viewer={null} review={review} onReviewChange={change}
        mode="conversation" focusFindingId={null} onNavigateToChanges={() => {}} />;
    }
    flushSync(() => root.render(<Surface />));
    await settle(); await settle();
    changeInput(input(), 'what are you doing?'); await settle();
  };
  try {
    for (const ambiguous of [false, true]) await scenario(`A ${ambiguous ? 'lost response keeps the review request identity' : 'rejected review send shows its error before reconciliation'}`, async () => {
      let hold = false;
      let reconciling = false;
      let release = (): void => {};
      let notify = (_message: AgentStreamMessage): void => {};
      const keys: string[] = [];
      const accepted = new Set<string>();
      let history = [...inputEvents, endEvent];
      await mount(completed, () => completed, async (_id, _text, _delivery, key) => {
        keys.push(key);
        if (ambiguous) {
          accepted.add(key);
          if (keys.length === 1) history = [...history, { ...inputEvents[0], eventId: 'review-retry', sequence: 4, turnId: 'chat-turn',
            type: 'user_message', payload: { text: 'what are you doing?', attachments: [], deliveryKind: 'TURN' } }];
        }
        if (!ambiguous || keys.length === 1) {
          hold = true;
          throw new Error(ambiguous ? UNCONFIRMED_REQUEST : 'Review request rejected before acceptance');
        }
        return completed;
      }, {
        getAgentReview: async () => {
          if (!hold) return completed;
          reconciling = true;
          return new Promise<AgentReviewDto>(resolve => { release = () => { hold = false; resolve(completed); }; });
        },
        readAgentReviewHistory: async () => ({ events: history, beforeCursor: null, afterCursor: null, latestSequence: history.at(-1)?.sequence ?? 0 }),
        subscribeAgentReview: async (taskId, _after, listener) => {
          notify = listener;
          listener({ subscriptionId: 'review-failure', taskId, kind: 'state', state: 'open' });
          return () => {};
        },
      });
      try {
        const send = () => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
        await until(() => send()?.disabled === false, 'The completed review must accept a message');
        send()?.click();
        const notice = ambiguous ? CHECKING_ACCEPTANCE : 'Review request rejected before acceptance';
        await until(() => host.querySelector('.agr-error')?.textContent === notice, 'The error must be visible before reconciliation returns');
        assert(reconciling && hold && input().value === 'what are you doing?', 'Hold reconciliation while preserving the message draft');
        if (ambiguous) {
          assert(host.querySelector('.agt-live')?.textContent === 'Checking acceptance…', 'An uncertain review send must not be labelled unsent');
          release();
          await until(() => send()?.disabled === false, 'Reconciliation should permit a safe retry');
          send()?.click();
          await until(() => keys.length === 2 && input().value === '', 'Retry the retained request');
          assert(keys[0] === keys[1] && accepted.size === 1, 'Retry after a lost response must identify the same accepted review turn');
          notify({ subscriptionId: 'review-failure', taskId: completed.task.id, kind: 'changed', sequence: 4, turnId: 'chat-turn' });
          await until(() => host.querySelector('[data-history-anchor="pending-message"]') === null, 'Durable review input must replace pending progress');
          assert([...host.querySelectorAll('.agt-user__bubble')].filter(bubble => bubble.textContent === 'what are you doing?').length === 1,
            'A retried review send must show one durable message');
        }
        else assert(host.querySelector('[data-history-anchor="pending-message"]') === null && accepted.size === 0,
          'A known rejection must restore the draft without pretending the review accepted it');
      }
      finally { release(); }
    });

    for (const rejected of [false, true]) await scenario(`A late ${rejected ? 'failure' : 'success'} cannot overwrite another review`, async () => {
      let release = (): void => {};
      let saved = completed;
      await mount(completed, () => saved, async () => new Promise<AgentReviewDto>((resolve, reject) => {
        release = () => rejected ? reject(new Error('Old review request failed')) : resolve(completed);
      }));
      host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.click();
      await settle();
      saved = { ...completed, id: 'another-review', task: { ...completed.task, id: 'another-task' } };
      flushSync(() => replaceReview(saved));
      await settle(); await settle();
      changedReviews = [];
      release();
      await settle(); await settle();
      assert(!changedReviews.some(review => review.id === completed.id) && host.textContent?.includes('Old review request failed') !== true,
        'A response for the previous review must not replace current data or error feedback');
      assert(input().value === '', 'A late failed send must not restore its draft into the next review');
    });

    await scenario('Accepted review input stays visible until metadata can present it, without clearing a newer send', async () => {
      const history: AgentEventEnvelope[] = [...inputEvents, endEvent];
      const firstText = 'what are you doing?';
      const secondText = 'Check the next detail.';
      let listener: Parameters<Bridge['subscribeAgentReview']>[2] = () => {};
      let releaseSend = (): void => {};
      let releaseMetadata = (): void => {};
      let releaseHistory = (): void => {};
      let holdMetadata = false;
      let metadataWaiting = false;
      let holdHistory = false;
      let historyWaiting = false;
      let sends = 0;
      const bubbles = (text: string) => [...host.querySelectorAll('.agt-user__bubble')].filter(node => node.textContent === text);
      let watching = false;
      let lostOrDuplicated = false;
      const observer = new MutationObserver(() => { if (watching) lostOrDuplicated ||= bubbles(firstText).length !== 1; });
      observer.observe(host, { childList: true, subtree: true, characterData: true });
      try {
        await mount(completed, () => completed, async () => {
          sends++;
          await new Promise<void>(resolve => { releaseSend = resolve; });
          return completed;
        }, {
          getAgentReview: async () => {
            if (holdMetadata) {
              metadataWaiting = true;
              await new Promise<void>(resolve => { releaseMetadata = () => { holdMetadata = false; resolve(); }; });
            }
            return completed;
          },
          getAgentTurn: async id => ({ ...contracts.turn, id, taskId: completed.task.id, state: 'COMPLETED',
            endedAt: '2026-09-06T16:20:00Z' } as AgentTurnDto),
          readAgentReviewHistory: async () => {
            if (holdHistory) {
              historyWaiting = true;
              await new Promise<void>(resolve => { releaseHistory = () => { holdHistory = false; resolve(); }; });
            }
            return { events: [...history], beforeCursor: null, afterCursor: null, latestSequence: history.at(-1)?.sequence ?? 0 };
          },
          subscribeAgentReview: async (id, _after, callback) => {
            listener = callback;
            listener({ kind: 'state', state: 'open', subscriptionId: 'review-visible', taskId: id });
            return () => {};
          },
        });
        const send = () => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
        await until(() => send()?.disabled === false, 'The completed review must accept the first message');
        holdMetadata = true;
        flushSync(() => send()?.click());
        assert(bubbles(firstText).length === 1, 'Show the optimistic review message immediately');
        watching = true;
        const source = inputEvents[0];
        assert(source.type === 'user_message', 'The fixture needs a user input');
        history.push({ ...source, eventId: 'first-accepted', sequence: 4, turnId: 'first-chat', payload: { text: firstText, attachments: [], deliveryKind: 'TURN' } });
        listener({ kind: 'changed', taskId: completed.task.id, subscriptionId: 'review-visible', sequence: 4, turnId: 'first-chat' });
        await until(() => metadataWaiting, 'History must arrive while its review metadata is held');
        await settle(); await settle();
        assert(host.querySelector('[data-history-anchor="pending-message"]')?.textContent?.includes(firstText) === true
          && bubbles(firstText).length === 1, 'Keep the pending bubble while the saved user input is hidden by the metadata guard');
        releaseMetadata();
        await until(() => host.querySelector('[data-history-anchor="pending-message"]') === null && bubbles(firstText).length === 1,
          'Metadata must replace pending progress with exactly one saved user bubble');
        assert(!lostOrDuplicated, 'No intermediate DOM update may remove or duplicate the sent message');
        releaseSend();
        await until(() => send()?.disabled === true && input().value === '', 'The first send must settle with an empty draft');
        await settle();

        holdHistory = true;
        history.push({ ...endEvent, eventId: 'first-completed', sequence: 5, turnId: 'first-chat' });
        listener({ kind: 'changed', taskId: completed.task.id, subscriptionId: 'review-visible', sequence: 5, turnId: 'first-chat' });
        await until(() => historyWaiting, 'Hold a late history update for the first send');
        changeInput(input(), secondText);
        await until(() => send()?.disabled === false, 'The completed first turn must allow another message');
        flushSync(() => send()?.click());
        releaseHistory();
        await settle(); await settle();
        assert(sends === 2 && host.querySelector('[data-history-anchor="pending-message"]')?.textContent?.includes(secondText) === true,
          'A late history and metadata update for the first send must not clear the newer pending message');
        history.push({ ...source, eventId: 'second-accepted', sequence: 6, turnId: 'second-chat', payload: { text: secondText, attachments: [], deliveryKind: 'TURN' } });
        listener({ kind: 'changed', taskId: completed.task.id, subscriptionId: 'review-visible', sequence: 6, turnId: 'second-chat' });
        await until(() => host.querySelector('[data-history-anchor="pending-message"]') === null && bubbles(secondText).length === 1,
          'The newer send must retire its pending bubble only when its own saved input becomes visible');
        assert(bubbles(firstText).length === 1 && !lostOrDuplicated, 'Keep both accepted messages exactly once');
      }
      finally { watching = false; observer.disconnect(); releaseMetadata(); releaseHistory(); releaseSend(); }
    });

    for (const delivery of ['STEER', 'QUEUE'] as const) await scenario(`An unrelated ${delivery} failure cannot alter a Send waiting for review metadata`, async () => {
      let saved = completed;
      const history: AgentEventEnvelope[] = [...inputEvents, endEvent];
      let listener: Parameters<Bridge['subscribeAgentReview']>[2] = () => {};
      let releaseSend = (): void => {};
      let releaseMetadata = (): void => {};
      const metadataGate = new Promise<void>(resolve => { releaseMetadata = resolve; });
      let holdMetadata = false;
      let metadataWaiting = false;
      const keys: string[] = [];
      try {
        await mount(completed, () => saved, async (_id, _text, kind, key) => {
          keys.push(key);
          if (kind !== 'TURN') throw new Error(delivery === 'STEER' ? 'The review agent is not running a turn to steer' : UNCONFIRMED_REQUEST);
          await new Promise<void>(resolve => { releaseSend = resolve; });
          return saved;
        }, {
          getAgentReview: async () => {
            if (holdMetadata) { metadataWaiting = true; await metadataGate; }
            return saved;
          },
          getAgentTurn: async id => ({ ...contracts.turn, id, taskId: completed.task.id, state: id === 'accepted-chat' ? 'RUNNING' : 'COMPLETED',
            endedAt: id === 'accepted-chat' ? null : '2026-09-06T16:20:00Z' } as AgentTurnDto),
          readAgentReviewHistory: async () => ({ events: [...history], beforeCursor: null, afterCursor: null, latestSequence: history.at(-1)?.sequence ?? 0 }),
          subscribeAgentReview: async (id, _after, callback) => {
            listener = callback;
            listener({ kind: 'state', state: 'open', subscriptionId: 'review-owner', taskId: id });
            return () => {};
          },
        });
        holdMetadata = true;
        flushSync(() => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.click());
        const source = inputEvents[0];
        assert(source.type === 'user_message' && running.activeTurn !== null, 'The fixture needs a user input and running turn');
        history.push({ ...source, eventId: 'accepted-send', sequence: 4, turnId: 'accepted-chat',
          payload: { text: 'what are you doing?', attachments: [], deliveryKind: 'TURN' } });
        saved = { ...completed, activeTurn: { ...running.activeTurn, id: 'accepted-chat' }, task: { ...completed.task, mutationClaim: 'TURN' } };
        listener({ kind: 'changed', taskId: completed.task.id, subscriptionId: 'review-owner', sequence: 4, turnId: 'accepted-chat' });
        await until(() => metadataWaiting, 'Hold metadata after accepting the first Send');
        releaseSend();
        const control = () => button(delivery === 'STEER' ? 'Steer' : 'Run next');
        await until(() => host.querySelector<HTMLButtonElement>('.agt-send.is-stop')?.disabled === false,
          'The accepted Send must expose the running-turn controls');
        changeInput(input(), 'An unrelated instruction.');
        await until(() => control()?.disabled === false, 'Send acceptance must enable the running-turn action before metadata returns');
        control()?.click();
        const notice = delivery === 'STEER' ? 'The review agent is not running a turn to steer' : CHECKING_ACCEPTANCE;
        await until(() => host.querySelector('.agr-error')?.textContent === notice, 'Show the unrelated action failure before reconciliation');
        assert(keys.length === 2 && keys[0] !== keys[1], 'The later action must have its own request identity');
        assert(host.querySelector('[data-history-anchor="pending-message"]')?.textContent?.includes('what are you doing?') === true
          && host.querySelector('.agt-live')?.textContent === 'Sending…', 'An unrelated failure must neither remove nor unconfirm the accepted Send bubble');
        holdMetadata = false;
        releaseMetadata();
        await until(() => host.querySelector('[data-history-anchor="pending-message"]') === null, 'The owning Send retires when metadata can present its event');
        assert([...host.querySelectorAll('.agt-user__bubble')].filter(node => node.textContent === 'what are you doing?').length === 1,
          'The original accepted message remains exactly once after the unrelated failure');
      }
      finally { holdMetadata = false; releaseSend(); releaseMetadata(); }
    });

    for (const olderHistory of [false, true]) await scenario(`A sent review message and its log appear before turn startup returns${olderHistory ? ' from older history' : ''}`, async () => {
      let saved = completed;
      const history: AgentEventEnvelope[] = olderHistory ? Array.from({ length: 4000 }, (_, index): AgentEventEnvelope => ({
        ...inputEvents[0], eventId: `old-${index + 1}`, sequence: index + 1, turnId: 'turn-1', type: 'assistant_message',
        source: { ...inputEvents[0].source, providerItemId: `old-item-${index + 1}`,
          parentProviderThreadId: index % 1000 >= 970 ? null : 'root-thread', logicalAgentPath: index % 1000 >= 970 ? '/' : '/child' },
        payload: { messageKind: 'COMMENTARY', phase: 'FINAL', text: `Checkpoint ${index + 1}` },
      })) : [...inputEvents, endEvent];
      const initialSequence = history.length;
      let historyReads = 0;
      let latestReply: ((value: AgentHistoryPageDto) => void) | null = null;
      let listener: Parameters<Bridge['subscribeAgentReview']>[2] = () => { throw new Error('Stream is not subscribed'); };
      let release = () => {};
      const gate = new Promise<void>(resolve => { release = resolve; });
      let sends = 0;
      let settled = false;
      try {
        await mount(completed, () => saved, async (_id, text, delivery) => {
          assert(text === 'what are you doing?' && delivery === 'TURN', 'Send must submit the written review message');
          sends++;
          await gate;
          settled = true;
          return saved;
        }, {
          readAgentReviewHistory: async (_id, _taskId, before) => {
            if (!olderHistory) return { events: [...history], beforeCursor: null, afterCursor: null, latestSequence: history.at(-1)?.sequence ?? 0 };
            if (before === undefined && ++historyReads > 1) return new Promise(resolve => { latestReply = resolve; });
            const end = before === undefined ? initialSequence : Number(before);
            return { events: history.slice(Math.max(0, end - 1000), end), beforeCursor: end > 1000 ? String(end - 1000) : null,
              afterCursor: end < initialSequence ? String(end) : null, latestSequence: initialSequence };
          },
          subscribeAgentReview: async (id, _after, callback) => {
            listener = callback;
            listener({ kind: 'state', state: 'open', subscriptionId: 'review-stream', taskId: id });
            return () => {};
          },
          getAgentTurn: async id => {
            if (id === 'chat-turn') await gate;
            return { ...contracts.turn, id, taskId: completed.task.id, state: id === 'chat-turn' ? 'RUNNING' : 'COMPLETED',
              endedAt: id === 'chat-turn' ? null : '2026-09-06T16:20:00Z' } as AgentTurnDto;
          },
        });
        if (olderHistory) {
          const transcript = host.querySelector<HTMLElement>('.agt-transcript');
          assert(transcript !== null, 'The review transcript must exist');
          transcript.style.cssText = 'height: 400px; overflow-y: auto';
          transcript.scrollTop = 0;
          transcript.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, bubbles: true }));
          await until(() => host.textContent?.includes('Checkpoint 1000') === true && !host.textContent?.includes('Checkpoint 4000'),
            'The reviewer should be reading an older history window');
        }
        const send = host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
        assert(send !== null && !send.disabled, 'A completed review turn must accept a new message');
        flushSync(() => send.click());
        assert(sends === 1 && !settled && input().value === '', 'Send must clear the composer while startup is still pending');
        assert(host.querySelector('[data-history-anchor="pending-message"]')?.textContent?.includes('what are you doing?') === true,
          'The review message must appear immediately without waiting for its request to return');
        assert(host.querySelector('.agt-live')?.textContent === 'Sending…', 'Review send must show its pending progress');
        const source = inputEvents[0];
        assert(source.type === 'user_message' && running.activeTurn !== null, 'The fixture needs a user message and active turn');
        history.push({ ...source, eventId: 'sent-review-question', sequence: initialSequence + 1, turnId: 'chat-turn', payload: { ...source.payload, text: 'what are you doing?' } },
          { ...endEvent, eventId: 'chat-started', sequence: initialSequence + 2, turnId: 'chat-turn', payload: { phase: 'TURN_STARTED', message: 'Turn started' } },
          { ...source, eventId: 'chat-progress', sequence: initialSequence + 3, turnId: 'chat-turn', type: 'assistant_message',
            payload: { messageKind: 'COMMENTARY', phase: 'FINAL', text: 'Checking the review request now.' } });
        saved = { ...completed, activeTurn: { ...running.activeTurn, id: 'chat-turn' }, task: { ...completed.task, mutationClaim: 'TURN' } };
        if (olderHistory) {
          assert(latestReply !== null, 'Send should request the latest review history');
          (latestReply as (value: AgentHistoryPageDto) => void)({ events: history.slice(-3), beforeCursor: null, afterCursor: null, latestSequence: initialSequence + 3 });
        }
        else listener({ kind: 'changed', taskId: completed.task.id, subscriptionId: 'review-stream', sequence: initialSequence + 3, turnId: 'chat-turn' });
        await until(() => host.textContent?.includes('Checking the review request now.') === true,
          'The review log must render from the stream before startup or turn details return');
        assert(!settled && sends === 1, 'Displaying progress must not wait for startup or send the request twice');
        assert(host.querySelector('[data-history-anchor="pending-message"]') === null
          && [...host.querySelectorAll('.agt-user__bubble')].filter(node => node.textContent?.includes('what are you doing?')).length === 1,
        'The durable review message must replace the pending bubble exactly once');
        release();
        await settle();
      }
      finally { release(); }
    });
    await scenario('The conversation resumes the review without sending or losing the chat draft', async () => {
      let saved: AgentReviewDto = { ...completed, runs: [{ ...completed.runs[0], state: 'INCOMPLETE', coverage: null,
        outcome: 'The provider cancelled before completing the review' }] };
      let resumes = 0;
      await mount(saved, () => saved, async () => { throw new Error('Resume must not send a discussion message'); }, {
        resumeAgentReview: async id => {
          assert(id === saved.id, 'Resume this review');
          resumes++;
          saved = running;
          return saved;
        },
      });
      const control = button('Resume review');
      assert(control !== undefined && !control.disabled, 'Offer resume in the conversation containing the failed turn');
      assert(host.textContent?.includes('Chat messages only continue the discussion.'), 'Explain why typing continue cannot finish the review');
      control.click(); await settle(); await settle();
      assert(resumes === 1 && button('Resume review') === undefined, 'Start the review through its resume endpoint');
      assert(input().value === 'what are you doing?', 'Keep the unsent discussion draft');
    });
    await scenario('A delayed review read shows the request card directly and coalesces stream refreshes', async () => {
      const source = inputEvents[0];
      assert(source.type === 'user_message', 'The fixture needs a user input');
      const prompt = 'Internal generated review prompt. Reply with review_reply. Selected card: '.repeat(200);
      const question: ReviewMessageDto = { ...reviewFixture().messages[0], id: 'new-question', author: 'USER',
        findingId: completed.findings[0].id, runId: completed.runs[0].id, cardId: null,
        body: 'Please check Spark behavior before keeping this finding.', turnId: 'question-turn', queuedInputId: null };
      let saved = completed;
      const history: AgentEventEnvelope[] = [...inputEvents, endEvent];
      let listener: Parameters<Bridge['subscribeAgentReview']>[2] = () => { throw new Error('Stream is not subscribed'); };
      let delayed = false;
      let release = () => {};
      const gate = new Promise<void>(resolve => { release = resolve; });
      let releaseTurn = () => {};
      const turnGate = new Promise<void>(resolve => { releaseTurn = resolve; });
      let reads = 0;
      let concurrent = 0;
      let maximum = 0;
      let exposed = false;
      const observer = new MutationObserver(() => { exposed ||= host.textContent?.includes('Internal generated review prompt') === true; });
      observer.observe(host, { childList: true, subtree: true, characterData: true });
      try {
        await mount(completed, () => saved, async () => saved, {
          getAgentReview: async () => {
            reads++; maximum = Math.max(maximum, ++concurrent);
            if (delayed) await gate;
            concurrent--;
            return saved;
          },
          getAgentTurn: async id => {
            if (delayed) await turnGate;
            return { ...contracts.turn, id, taskId: completed.task.id } as AgentTurnDto;
          },
          readAgentReviewHistory: async () => ({ events: [...history], beforeCursor: null, afterCursor: null,
            latestSequence: history.at(-1)?.sequence ?? 0 }),
          subscribeAgentReview: async (_id, _after, callback) => { listener = callback; return () => {}; },
        });
        const initialReads = reads;
        delayed = true;
        history.push({ ...source, eventId: 'question-event', turnId: question.turnId, sequence: 4, payload: { ...source.payload, text: prompt } });
        for (let index = 0; index < 18; index++) {
          listener({ kind: 'changed', taskId: completed.task.id, subscriptionId: 'review-stream', sequence: 4, turnId: question.turnId });
          await settle();
          assert(!host.textContent?.includes('Internal generated review prompt'), 'No intermediate frame may expose generated context');
        }
        assert(reads === initialReads + 1 && concurrent === 1, 'Stream notifications must share one pending review read');
        saved = { ...completed, messages: [question] };
        release();
        for (let attempt = 0; attempt < 30 && !host.textContent?.includes(question.body); attempt++) await settle();
        const card = host.querySelector('[data-history-anchor="question-turn"] .agt-review-request');
        assert(card?.textContent?.includes(question.body) === true, 'Show the complete compact card without waiting for turn details');
        assert(maximum === 1 && !exposed, 'Review reads must stay sequential and the raw prompt must never reach the DOM');
      }
      finally { release(); releaseTurn(); observer.disconnect(); }
    });
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
    dispose();
    Object.defineProperty(window, 'bridge', { configurable: true, value: previous });
  }
  return results;
}
