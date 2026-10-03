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
import React, { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { GoalLayer } from '../GoalLayer';
import type { GoalCardDto, GoalDto, GoalExchangeDto, GoalKnowledgeNoteDto, GoalRecommendationDto } from '../contracts';
import type { Goals } from '../useGoals';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';

/** The backend as the Goal window sees it: one workspace whose agent answers when the journey says so. */
const server = { goal: null as GoalDto | null, exchanges: [] as GoalExchangeDto[], notes: [] as GoalKnowledgeNoteDto[], asked: [] as string[], accepted: [] as string[],
  started: [] as string[], failNextAsk: false };
const CARD: GoalCardDto = { kind: 'RESEARCH', title: 'Read the Iceberg specification', reason: 'You start from the specification',
  scope: 'Table metadata, snapshots and manifests', pages: ['https://iceberg.apache.org/spec/'] };
const listeners = new Set<() => void>();
const changed = (): void => listeners.forEach((listener) => listener());
const unexpected = async (): Promise<never> => { throw new Error('Unexpected write'); };
const NOW = '2026-10-03T10:00:00Z';

function pending(id: string, message: string, automatic = false): GoalExchangeDto {
  return { id, goalId: 'goal', message, automatic, inputRevision: 0, state: 'PENDING', reply: null, failure: null, askedAt: NOW };
}
function setCards(cards: GoalRecommendationDto[]): void {
  server.goal = { ...server.goal, recommendations: cards };
}
function answer(reply: GoalExchangeDto['reply']): void {
  server.exchanges = server.exchanges.map((exchange) => (exchange.state === 'PENDING' ? { ...exchange, state: 'ANSWERED', reply } : exchange));
}
const conversation: Omit<Goals, 'goals' | 'pinned'> = {
  workItems: [], error: null, create: unexpected, pin: async () => {}, remove: unexpected, add: unexpected, removeItem: unexpected, search: async () => [],
  createWork: unexpected, decideWork: unexpected, linkWork: unexpected, retry: unexpected, dismissCard: unexpected, acceptCourse: unexpected, refresh: changed,
  knowledge: async () => server.notes,
  startCard: async (_goalId, recommendationId) => {
    server.started.push(recommendationId);
    const started: GoalRecommendationDto = { ...server.goal.recommendations[0], state: 'STARTED', taskId: 'task-research' };
    setCards([started]);
    changed();
    return started;
  },
  start: async (operationId, message) => {
    server.asked.push(operationId);
    server.goal = { id: 'goal', workspaceId: 'workspace', title: message, description: '', pinned: true, source: null, definition: null, revision: 0,
      course: null, items: [], recommendations: [], createdAt: NOW, updatedAt: NOW };
    server.exchanges = [pending(operationId, message)];
    changed();
    return server.goal;
  },
  exchanges: async () => server.exchanges,
  ask: async (_goalId, operationId, message) => {
    server.asked.push(operationId);
    if (server.failNextAsk) {
      server.failNextAsk = false;
      throw new Error('Connection interrupted');
    }
    server.exchanges = [...server.exchanges, pending(operationId, message)];
    return server.exchanges;
  },
  accept: async (_goalId, exchangeId, title) => {
    server.accepted.push(title);
    server.goal = { ...server.goal, title, description: 'Explain and change the Iceberg connector', revision: 1, definition: {
      successDefinition: 'Trace a scan end to end', startingKnowledge: 'Knows Trino', constraints: '', exchangeId, acceptedAt: NOW } };
    // The backend tells the agent the definition was accepted.
    server.exchanges = [...server.exchanges, pending(`accepted:${exchangeId}`, 'The user accepted the goal definition.', true)];
    changed();
    return server.goal;
  },
};

function Fixture(): React.JSX.Element {
  const [goal, setGoal] = useState(server.goal);
  useEffect(() => {
    const listener = (): void => setGoal(server.goal);
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  }, []);
  const goals: Goals = { ...conversation, goals: goal === null ? [] : [goal], pinned: goal };
  return (
    <GoalLayer page="home" workspaceId="workspace" repository="trinodb/trino" goals={goals} needsYou={new Set()} onOpenTask={() => {}}>
      <main style={{ height: '100%' }}><h1>Workspace home</h1></main>
    </GoalLayer>
  );
}

function button(label: string): HTMLButtonElement {
  const found = [...document.querySelectorAll('button')].find((candidate) => candidate.textContent?.trim() === label || candidate.getAttribute('aria-label') === label);
  assert(found !== undefined, `Missing button ${label}`);
  return found;
}
function composer(): HTMLTextAreaElement {
  const found = document.querySelector<HTMLTextAreaElement>('.goal-composer textarea');
  assert(found !== null, 'Missing composer');
  return found;
}
function text(): string {
  return document.querySelector('.goal-window')?.textContent ?? '';
}
const asked = (): number => server.asked.length;
const starts = (): number => server.started.length;

/** Waits for the open conversation's next read while its agent answers. */
async function nextRead(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 1_700));
  await settle();
}

export async function run(): Promise<boolean> {
  const host = document.createElement('div');
  host.style.cssText = 'position:absolute;inset:0';
  document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<Fixture />)); await settle();
    document.querySelector<HTMLButtonElement>('.goal-pill')?.click(); await settle();
    assert(text().includes('What do you want to achieve?'), 'An empty workspace opens on what the user wants to achieve');
    assert(document.querySelectorAll('.goal-choice').length === 3, 'Three directions are offered without a model call');

    changeInput(composer(), 'I want to become an Iceberg connector expert'); await settle();
    button('Send').click(); await settle(); await settle();
    assert(server.goal !== null && asked() === 1, 'Free text starts the goal and asks its agent once');
    assert(text().includes('Thinking…') && button('Send').disabled, 'The pending answer shows and holds the next message');
    assert(composer().value === '', 'The sent message leaves the composer');

    answer({ assessment: 'You want to understand the connector deeply.', proposal: null, recommendation: null, course: null,
      question: { text: 'Do you already know the Iceberg specification?', suggestions: ['Yes', 'Not yet'] } });
    await nextRead();
    assert(text().includes('Do you already know the Iceberg specification?'), 'The question arrives without reopening the window');

    server.failNextAsk = true;
    changeInput(composer(), 'Not yet, and I have five hours a week'); await settle();
    button('Send').click(); await settle(); await settle();
    assert(text().includes('Connection interrupted') && composer().value === 'Not yet, and I have five hours a week', 'A failed send keeps the draft');
    button('Send').click(); await settle(); await settle();
    assert(asked() === 3 && server.asked[1] === server.asked[2], 'Resending the same draft reuses its operation');

    answer({ assessment: 'You can spend five hours a week.', proposal: null, recommendation: null, course: null,
      question: { text: 'Do you want to learn first or start on real work?', suggestions: ['Learn first', 'Real work first'] } });
    await nextRead();
    button('Learn first').click(); await settle(); await settle();
    assert(asked() === 4 && server.exchanges.at(-1)?.message === 'Learn first', 'A suggested answer is sent as the user\'s reply');

    answer({ assessment: 'Start from the specification, then the connector.', question: null, recommendation: null, course: null, proposal: {
      title: 'Iceberg connector expert', objective: 'Explain and change the Iceberg connector', successDefinition: 'Trace a scan end to end',
      startingKnowledge: 'Knows Trino', constraints: 'Five hours a week' } });
    await nextRead();
    const title = document.querySelector<HTMLInputElement>('.goal-proposal input');
    assert(title !== null && title.value === 'Iceberg connector expert', 'The proposal offers its title for editing');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(title, 'Trino Iceberg connector expert');
    title.dispatchEvent(new Event('input', { bubbles: true })); await settle();
    button('Accept goal').click(); await settle(); await settle();
    assert(server.accepted.length === 1 && server.accepted[0] === 'Trino Iceberg connector expert', 'Accepting saves the edited title');
    assert(document.querySelector('.goal-proposal') === null && text().includes('Success means'), 'The accepted definition replaces the proposal');
    assert(document.querySelector('.goal-panel__title')?.textContent === 'Trino Iceberg connector expert', 'The goal takes the accepted title');
    assert(text().includes('The user accepted the goal definition.') && text().includes('Thinking…'), 'Accepting tells the agent, which answers at once');

    // The agent judges research is the next step; the backend floats its card when it saves the answer.
    answer({ assessment: 'Read the specification before the connector.', question: null, proposal: null, recommendation: CARD, course: null });
    setCards([{ id: 'card-1', goalId: 'goal', card: CARD, state: 'SHOWN', taskId: null, createdAt: NOW }]);
    await nextRead(); await settle();
    const card = document.querySelector('.goal-cards .goal-rec');
    assert(card !== null && card.textContent?.includes('Read the Iceberg specification') === true, 'The recommended research floats under the Goal control');
    assert(starts() === 0, 'Nothing starts until Start');
    [...card.querySelectorAll('button')].find((candidate) => candidate.textContent === 'Start')?.click(); await settle(); await settle();
    assert(starts() === 1 && document.querySelector('.goal-cards') === null, 'Start begins the run and the card leaves the control');
    assert(text().includes('Researching: Read the Iceberg specification'), 'The Goal window shows the running research');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
