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
import { useCallback, useState } from 'react';
import { flushSync } from 'react-dom';
import { assert, changeInput, settle, until, element } from '../../fixtures/harness';
import '../../css/agent-review.css';
import AgentReviewTab from '../../pulls/agent-review/AgentReviewTab';
import { reviewFixture } from '../../pulls/agent-review/fixtures/review';
import type { AgentReviewDto } from '../../pulls/agent-review/contracts';
import type { AgentHistoryPageDto, AgentStreamMessage, AgentTaskDto, Bridge } from '../../types';
import AgentTaskScreen from '../AgentTaskScreen';
import type { AgentEventEnvelope } from '../events';
import { agentTurn, createPreviewBridge, previewTask } from './previewBridge';

const TASK_ID = 'large-history';
const AT = '2026-09-06T16:03:00Z';
const SOURCE: AgentEventEnvelope['source'] = { providerThreadId: 'root', providerTurnId: null, providerItemId: null,
  providerEventId: null, parentProviderThreadId: null, logicalAgentPath: '/' };
const base = (sequence: number, turnId: string) => ({ schemaVersion: 2 as const, eventId: `event-${sequence}`,
  sequence, taskId: TASK_ID, turnId, occurredAt: AT, source: SOURCE });
const message = (sequence: number, turnId: string, text: string): AgentEventEnvelope => ({ ...base(sequence, turnId),
  type: 'user_message', payload: { text, attachments: [], deliveryKind: 'TURN' } });
const output = (sequence: number, turnId: string, text: string, final = false): Extract<AgentEventEnvelope, { type: 'assistant_message' }> => ({ ...base(sequence, turnId),
  type: 'assistant_message', payload: { text, messageKind: final ? 'ANSWER' : 'COMMENTARY', phase: 'FINAL' } });
const round = (value: number) => Math.round(value * 10) / 10;

async function exercise(surface: 'task' | 'review') {
  // One hundred turns exceed the retained window. Each large answer includes a long tool-like block.
  const largeAnswer = '## Result\n\nThe **ordering** remains consistent with the `turnId`.\n\n```text\n'
    + 'Validated an existing request without changing its durable identity. '.repeat(256) + '\n```';
  const events = Array.from({ length: 6000 }, (_, index) => {
    const turnId = `history-${Math.floor(index / 60)}`;
    return index % 60 === 0 ? message(index + 1, turnId, `Historical request ${turnId}`)
      : output(index + 1, turnId, index % 60 === 59 ? `${largeAnswer}\n\nHistory result ${turnId}` : `Inspecting file ${index}`, index % 60 === 59);
  });
  const old = agentTurn('COMPLETED', { taskId: TASK_ID, id: 'history-99', endedAt: AT });
  const next = agentTurn('RUNNING', { taskId: TASK_ID, id: 'next' });
  let task: AgentTaskDto = { ...previewTask('workspace-1', TASK_ID), interactive: true, mutationClaim: null };
  const initialReview = reviewFixture({ runs: [], messages: [], byteQuayTurnIds: [], findings: [] });
  let review: AgentReviewDto = { ...initialReview, task: { ...initialReview.task, id: TASK_ID } };
  let reads = 0;
  let readEvents = 0;
  let readBytes = 0;
  let submitted = false;
  let responded = false;
  let release = (): void => {};
  let notify = (_message: AgentStreamMessage): void => {};
  const read = async (_id: string, before?: string): Promise<AgentHistoryPageDto> => {
    const end = before === undefined ? events.length : Number(before);
    const start = Math.max(0, end - 1000);
    const page = { events: events.slice(start, end), beforeCursor: start === 0 ? null : String(start),
      afterCursor: end === events.length ? null : String(end), latestSequence: events.length };
    // Include serialization/clone cost instead of sharing references with the renderer's cache.
    const wire = JSON.stringify(page);
    reads++; readEvents += page.events.length; readBytes += wire.length;
    return JSON.parse(wire) as AgentHistoryPageDto;
  };
  const subscribe: Bridge['subscribeAgentTask'] = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'large-history', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  const start = async () => {
    submitted = true;
    await new Promise<void>(resolve => { release = resolve; });
    responded = true;
  };
  const bridge = createPreviewBridge('workspace-1');
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async id => id === next.id ? next : { ...old, id };
  bridge.readAgentHistory = read;
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = subscribe;
  bridge.startAgentTurn = async () => { await start(); return next; };
  const extra: Pick<Bridge, 'getLinkedWorkspaceWork' | 'getRelatedWorkspaceRequest' | 'getBackportChains' | 'getAgentReview'
    | 'readAgentReviewHistory' | 'readAgentReviewApprovals' | 'subscribeAgentReview' | 'messageAgentReview'> = {
    getLinkedWorkspaceWork: async () => [], getRelatedWorkspaceRequest: async () => null,
    getBackportChains: async () => [], getAgentReview: async () => review,
    readAgentReviewHistory: (_reviewId: string, taskId: string, before?: string) => read(taskId, before),
    readAgentReviewApprovals: async () => [], subscribeAgentReview: subscribe,
    messageAgentReview: async () => { await start(); return review; },
  };
  window.bridge = { ...bridge, ...extra } as unknown as Bridge;
  const { host, root, dispose } = createFixtureRoot();
  host.style.cssText = 'height:640px;width:840px;display:flex;flex-direction:column';
  function Review() {
    const [current, setCurrent] = useState(review);
    const change = useCallback((updated: AgentReviewDto) => setCurrent(updated), []);
    return <AgentReviewTab workspaceId="workspace-1" viewer={null} review={current} onReviewChange={change}
      mode="conversation" focusFindingId={null} onNavigateToChanges={() => {}} />;
  }
  const liveText = (text: string) => [...host.querySelectorAll<HTMLElement>('.agt-user__bubble, .agt-text')]
    .find(node => node.textContent?.includes(text));
  const inViewport = (node: HTMLElement | undefined) => {
    const bounds = node?.getBoundingClientRect();
    const clip = host.querySelector('.agt-transcript')?.getBoundingClientRect();
    return bounds !== undefined && clip !== undefined && bounds.bottom > Math.max(0, clip.top)
      && bounds.top < Math.min(innerHeight, clip.bottom) && bounds.width > 0;
  };
  const changed = () => notify({ subscriptionId: 'large-history', taskId: TASK_ID, kind: 'changed', sequence: events.length, turnId: next.id });
  try {
    const mounted = performance.now();
    flushSync(() => root.render(surface === 'task'
      ? <AgentTaskScreen workspaceId="workspace-1" taskId={TASK_ID} bridge={bridge} onBack={() => {}} /> : <Review />));
    await until(() => host.textContent?.includes('History result history-99') === true, 'The large history must load');
    const coldMountMs = round(performance.now() - mounted);
    assert(reads === 3 && readEvents === 3000, 'Mount must read only the bounded recent window');
    const initialBytes = readBytes;
    assert(host.textContent?.includes('Historical request history-0') !== true, 'Old history must not leak into the recent window');
    const input = element<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]', host, 'The real wrapper must expose its composer');
    changeInput(input, 'Check this large history');
    await until(() => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.disabled === false, 'The old turn must be sendable');
    // Measure sending from the bottom of an already-open conversation.
    const transcript = element<HTMLElement>('.agt-transcript', host, 'The transcript must be mounted');
    transcript.scrollTop = transcript.scrollHeight;
    transcript.dispatchEvent(new Event('scroll'));
    await settle();
    const clicked = performance.now();
    host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.click();
    await until(() => liveText('Check this large history') !== undefined, 'Send must render the pending message');
    const sendCommitMs = round(performance.now() - clicked);
    await until(() => inViewport(liveText('Check this large history')), 'The pending message must enter the viewport');
    const sendVisibleMs = round(performance.now() - clicked);
    assert(submitted && !responded, 'Local progress must not wait for bridge acceptance');
    task = { ...task, mutationClaim: { kind: 'TURN', ownerId: next.id, acquiredAt: AT } };
    review = { ...review, activeTurn: next, task: { ...review.task, mutationClaim: 'TURN' } };
    events.push(message(6001, next.id, 'Check this large history'), output(6002, next.id, 'First live log after large history'));
    const overlapReads = reads;
    const arrived = performance.now();
    changed();
    await until(() => liveText('First live log after large history') !== undefined, 'Live output must render before acceptance');
    const firstLogCommitMs = round(performance.now() - arrived);
    await until(() => inViewport(liveText('First live log after large history')), 'The first live log must enter the viewport');
    const firstLogVisibleMs = round(performance.now() - arrived);
    assert(!responded && reads === overlapReads + 1, 'An overlapping live page must reuse its cached prefix');
    assert([...host.querySelectorAll('.agt-user__bubble')].filter(node => node.textContent === 'Check this large history').length === 1,
      'Large history must still reconcile pending and durable input once');

    const rolloverReads = reads;
    const rolloverAt = performance.now();
    for (let index = 0; index < 1100; index++) events.push({ ...output(events.length + 1, next.id, 'delta '),
      source: { ...SOURCE, providerItemId: 'large-output' },
      payload: { text: index === 1099 ? 'Rollover complete' : 'delta ', messageKind: 'COMMENTARY', phase: 'PARTIAL' } });
    changed();
    await until(() => liveText('Rollover complete') !== undefined, 'A full page rollover must preserve the final output');
    const rolloverMs = round(performance.now() - rolloverAt);
    assert(reads === rolloverReads + 3, 'A rollover must reload three bounded transport pages');
    const burstReads = reads;
    const burstAt = performance.now();
    for (let index = 0; index < 50; index++) {
      events.push(output(events.length + 1, next.id, `Burst output ${index}`));
      changed();
    }
    await until(() => liveText('Burst output 49') !== undefined, 'A burst must eventually show its newest output');
    const burstMs = round(performance.now() - burstAt);
    assert(reads - burstReads <= 2, 'A burst must coalesce refreshes instead of reading for every notification');
    release();
    await settle();
    return { name: surface, coldMountMs, sendCommitMs, sendVisibleMs, firstLogCommitMs, firstLogVisibleMs,
      rolloverMs, burstMs, initialBytes, reads, readEvents };
  }
  finally { release(); dispose(); }
}

export async function run(repeats = 1) {
  const results = [];
  for (let repeat = 0; repeat < repeats; repeat++) for (const surface of ['task', 'review'] as const) {
    try { results.push({ repeat, ...await exercise(surface) }); }
    catch (error) { results.push({ repeat, name: surface, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
