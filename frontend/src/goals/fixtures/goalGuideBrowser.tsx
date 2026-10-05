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
import type { GoalCardDto, GoalDto, GoalExchangeDto, GoalItemDto, GoalKnowledgeNoteDto, GoalRecommendationDto } from '../contracts';
import type { Goals } from '../useGoals';
import { assert, changeInput, settle } from '../../fixtures/harness';
import type { SpecCheckRequest } from '../../repository-context/specChecks';
import { NO_AGENT_CAPABILITIES } from '../../agents/capabilities';
import type { AgentModelSettingDto, ModelSettingsDto } from '../../types';
import '../../css/base.css';
import '../../css/settings-window.css';

/** The backend as the Goal window sees it: one workspace whose agent answers when the journey says so. */
const server = { goal: null as GoalDto | null, exchanges: [] as GoalExchangeDto[], notes: [] as GoalKnowledgeNoteDto[], asked: [] as string[], accepted: [] as string[],
  started: [] as string[], cancelled: [] as string[], stopped: [] as string[], reassessed: [] as string[], failNextAsk: false, model: null as AgentModelSettingDto | null,
  researchStopped: false };
/** The workspace's Development model, which a goal follows until it has its own. */
const WORKSPACE_MODEL: AgentModelSettingDto = { type: 'DEVELOPMENT', explicit: false, configId: 'codex', label: 'Codex', model: 'sol', modelLabel: 'Sol', effort: 'high' };
const MODELS: ModelSettingsDto = { agents: [], apiKeys: [], keyProviders: [], providers: [{ id: 'codex', label: 'Codex', readiness: 'READY', diagnostic: '',
  capabilities: NO_AGENT_CAPABILITIES, selectedModel: 'sol', selectedEffort: 'high', models: [
    { id: 'sol', name: 'Sol', description: '', efforts: ['low', 'high'], defaultEffort: 'high' },
    { id: 'luna', name: 'Luna', description: '', efforts: ['low', 'high'], defaultEffort: 'low' }] }] };
const CARD: GoalCardDto = { kind: 'RESEARCH', title: 'Read the Iceberg specification', reason: 'You start from the specification',
  scope: 'Table metadata, snapshots and manifests', pages: ['https://iceberg.apache.org/spec/'], workItemId: null, issue: null, ticket: null, module: null, reproductions: null };
const PRACTICE: GoalCardDto = { kind: 'WORK', title: 'Fix #31252', reason: 'You learned how checkpoints reconcile actions',
  scope: 'Key checkpoint add and remove actions by path and deletion vector, and add a test that loses rows today.', pages: [], workItemId: null, issue: 31252, ticket: null, module: null, reproductions: null,
  uncertainty: 'Pull request #31270 is open and linked to close issue #31252; it may already fix it.' };
const CHECK: GoalCardDto = { kind: 'SPEC_CHECK', title: 'Check the Delta checkpoint writer', reason: 'You know how checkpoints reconcile actions',
  scope: 'Checkpoint action reconciliation', pages: ['https://github.com/delta-io/delta/blob/master/PROTOCOL.md'], workItemId: null, issue: null, ticket: null,
  module: 'plugin/trino-delta-lake/', reproductions: 3 };
const started: { issue: number; instruction: string }[] = [];
const specChecks: SpecCheckRequest[] = [];
const listeners = new Set<() => void>();
const changed = (): void => listeners.forEach((listener) => listener());
const unexpected = async (): Promise<never> => { throw new Error('Unexpected write'); };
const NOW = '2026-10-03T10:00:00Z';

function pending(id: string, message: string, automatic = false): GoalExchangeDto {
  return { id, goalId: 'goal', message, automatic, lessonId: null, inputRevision: 0, state: 'PENDING', reply: null, failure: null, askedAt: NOW };
}
function setCards(cards: GoalRecommendationDto[]): void {
  server.goal = { ...server.goal, recommendations: cards };
}
function answer(reply: GoalExchangeDto['reply']): void {
  server.exchanges = server.exchanges.map((exchange) => (exchange.state === 'PENDING' ? { ...exchange, state: 'ANSWERED', reply } : exchange));
}
const conversation: Omit<Goals, 'goals' | 'pinned'> = {
  workItems: [], error: null, create: unexpected, pin: async () => {}, rename: unexpected, add: unexpected, removeItem: unexpected, search: async () => [],
  remove: async (goalId) => {
    server.cancelled.push(goalId);
    server.goal = null;
    server.exchanges = [];
    changed();
  },
  createWork: unexpected, decideWork: unexpected, linkWork: unexpected, orderWork: unexpected, dismissCard: unexpected, acceptCourse: unexpected, refresh: changed,
  stop: async (_goalId, exchangeId) => {
    server.stopped.push(exchangeId);
    server.exchanges = server.exchanges.map((exchange) => (exchange.id === exchangeId && exchange.state === 'PENDING'
      ? { ...exchange, state: 'FAILED', failure: 'Stopped before the agent answered.' } : exchange));
    return server.exchanges;
  },
  retry: async (_goalId, exchangeId) => {
    server.exchanges = server.exchanges.map((exchange) => (exchange.id === exchangeId ? { ...exchange, state: 'PENDING', failure: null } : exchange));
    return server.exchanges;
  },
  openLesson: unexpected, readLesson: unexpected, finishLesson: unexpected, setTopic: unexpected,
  knowledge: async () => server.notes,
  gaps: async () => [],
  addGap: unexpected,
  fileIssue: unexpected,
  summarize: unexpected,
  pause: async (_goalId, paused) => {
    server.goal = { ...server.goal, paused };
    changed();
    return server.goal;
  },
  reassess: async (_goalId, operationId) => {
    server.reassessed.push(operationId);
    server.exchanges = [...server.exchanges, pending(operationId, 'The user asked for an update: reassess the goal from its current facts.', true)];
    return server.exchanges;
  },
  model: async () => ({ setting: server.model ?? WORKSPACE_MODEL, settings: MODELS }),
  chooseModel: async (_goalId, choice) => {
    server.model = choice === null ? null : { ...WORKSPACE_MODEL, explicit: true, ...choice, modelLabel: choice.model === 'luna' ? 'Luna' : 'Sol' };
    return server.model ?? WORKSPACE_MODEL;
  },
  // The research run the started card began: one note saved so far, still reading.
  researchRun: async () => ({ buildRequestSequence: 1, canStart: false, build: { kind: 'RESEARCH', requestKey: 'research', taskId: 'task-research', checkId: '',
    task: null, activeTurn: { cancellationRequestedAt: null }, pendingApprovals: 0, outcome: server.researchStopped ? 'STOPPED' : 'RUNNING', detail: null,
    activity: [{ at: NOW, text: 'Saved note · Manifest lists', tone: 'DONE' }] } }),
  stopResearch: async (taskId) => {
    server.researchStopped = taskId === 'task-research';
    return conversation.researchRun();
  },
  startCard: async (_goalId, recommendationId) => {
    server.started.push(recommendationId);
    const card = server.goal.recommendations.find((candidate) => candidate.id === recommendationId);
    // Research begins its run; a work card's issue becomes the goal's work, whose task the user sends from the composer.
    const running: GoalRecommendationDto = { ...card, state: 'STARTED', taskId: card.card.kind === 'RESEARCH' ? 'task-research' : null,
      workItemId: card.card.kind === 'WORK' ? 'work-1' : null };
    setCards(server.goal.recommendations.map((candidate) => (candidate.id === recommendationId ? running : candidate)));
    changed();
    return running;
  },
  start: async (operationId, message) => {
    server.asked.push(operationId);
    server.goal = { id: 'goal', workspaceId: 'workspace', title: message, description: '', pinned: true, paused: false, source: null, definition: null, revision: 0,
      course: null, items: [], recommendations: [], topics: [], createdAt: NOW, updatedAt: NOW };
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
    <GoalLayer page="home" workspaceId="workspace" repository="trinodb/trino" goals={goals} needsYou={new Set()} onOpenTask={() => {}}
      onStartIssue={(issue, instruction) => started.push({ issue, instruction })} onSpecCheck={(spec) => specChecks.push(spec)}>
      <main style={{ height: '100%' }}><h1>Workspace home</h1></main>
    </GoalLayer>
  );
}

function button(label: string): HTMLButtonElement {
  const found = [...document.querySelectorAll('button')].find((candidate) => candidate.textContent?.trim() === label || candidate.getAttribute('aria-label') === label);
  assert(found !== undefined, `Missing button ${label}`);
  return found;
}
function option(label: string): HTMLButtonElement {
  const found = [...document.querySelectorAll<HTMLButtonElement>('[role="option"]')].find((candidate) => candidate.querySelector('.setw-menu-label')?.textContent === label);
  assert(found !== undefined, `Missing option ${label}`);
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
const composers = (): number => started.length;

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
    assert(document.activeElement === composer(), 'The question is ready for typing');
    assert(document.querySelectorAll('.goal-choice').length === 3, 'Three directions are offered without a model call');

    changeInput(composer(), 'I want to become an Iceberg connector expert'); await settle();
    button('Send').click(); await settle(); await settle();
    assert(server.goal !== null && asked() === 1, 'Free text starts the goal and asks its agent once');
    assert(text().includes('Thinking…') && button('Send').disabled, 'The pending answer shows and holds the next message');
    assert(composer().value === '', 'The sent message leaves the composer');
    assert(/Thinking…\d+:\d\d/.test(text()), 'The pending reply shows how long the agent has worked');
    button('Stop').click(); await settle(); await settle();
    assert(server.stopped.length === 1 && text().includes('Stopped before the agent answered.') && !text().includes('Thinking…'), 'Stop ends a slow reply and keeps the message');
    button('Ask again').click(); await settle(); await settle();
    assert(text().includes('Thinking…') && asked() === 1, 'Ask again sends the stopped message once more');

    const model = (): string => button('Development model').textContent ?? '';
    // Read through a function: an assertion narrows the server's fields for the rest of the run.
    const chosen = (): AgentModelSettingDto | null => server.model;
    assert(model() === 'Sol · workspace default', 'The window names the workspace model the goal follows');
    button('Development model').click(); await settle();
    option('Luna').click(); await settle(); await settle();
    assert(chosen()?.model === 'luna' && chosen()?.effort === 'high' && model() === 'Luna' && button('Development effort').textContent === 'high',
      'Picking another model gives this goal its own and keeps the effort it offers');
    button('Development effort').click(); await settle();
    option('low').click(); await settle(); await settle();
    assert(chosen()?.effort === 'low', 'The goal can work at its own effort');
    button('Development model').click(); await settle();
    option('Workspace default').click(); await settle(); await settle();
    assert(chosen() === null && model() === 'Sol · workspace default', 'The goal can follow the workspace model again');

    answer({ assessment: 'You want to understand the connector deeply.', proposal: null, recommendation: null, course: null,
      question: { text: 'Do you already know the Iceberg specification?', suggestions: ['Yes', 'Not yet'] } });
    await nextRead();
    assert(text().includes('Do you already know the Iceberg specification?'), 'The question arrives without reopening the window');

    button('Pause guidance').click(); await settle(); await settle();
    // Read through a function: an assertion narrows the server's fields for the rest of the run.
    const paused = (): boolean => server.goal?.paused === true;
    assert(paused() && document.querySelector('.goal-paused')?.textContent === 'Paused', 'Pause guidance marks the goal paused');
    button('Update now').click(); await settle(); await settle();
    assert(server.reassessed.length === 1 && text().includes('reassess the goal from its current facts') && button('Update now').disabled,
      'Update now asks a paused goal\'s agent to reassess, once at a time');
    answer({ assessment: 'Nothing changed; you still start from the specification.', proposal: null, recommendation: null, course: null,
      question: { text: 'Do you already know the Iceberg specification?', suggestions: ['Yes', 'Not yet'] } });
    await nextRead();
    button('Resume guidance').click(); await settle(); await settle();
    assert(!paused() && document.querySelector('.goal-paused') === null, 'Resume guidance clears the mark');

    server.failNextAsk = true;
    changeInput(composer(), 'Not yet, and I have five hours a week'); await settle();
    button('Send').click(); await settle(); await settle();
    assert(text().includes('Connection interrupted') && composer().value === 'Not yet, and I have five hours a week', 'A failed send keeps the draft');
    button('Send').click(); await settle(); await settle();
    assert(asked() === 3 && server.asked[1] === server.asked[2], 'Resending the same draft reuses its operation');

    answer({ assessment: 'You can spend five hours a week.', proposal: null, recommendation: null, course: null,
      question: { text: 'Do you want to learn first or start on real work?', suggestions: ['Learn first', 'Real work first'] } });
    await nextRead();
    button('Other options').click(); await settle(); await settle();
    assert(asked() === 4 && server.exchanges.at(-1)?.message === 'None of these fit. Show me other options.', 'Other options asks the agent for different answers');
    answer({ assessment: 'Here are other ways to spend the time.', proposal: null, recommendation: null, course: null,
      question: { text: 'Do you want to learn first or start on real work?', suggestions: ['Learn first', 'Read pull requests first', 'One evening of each'] } });
    await nextRead();
    button('Learn first').click(); await settle();
    button('Read pull requests first').click(); await settle();
    button('Read pull requests first').click(); await settle();
    assert(asked() === 4 && button('Learn first').getAttribute('aria-pressed') === 'true' && button('Read pull requests first').getAttribute('aria-pressed') === 'false',
      'Picking answers marks them and sends nothing yet');
    changeInput(composer(), 'Mostly the scheduler'); await settle();
    button('Send').click(); await settle(); await settle();
    assert(asked() === 5 && server.exchanges.at(-1)?.message === 'Learn first\nMostly the scheduler', 'Send sends the picked answers with the user\'s words');

    answer({ assessment: 'Start from the specification, then the connector.', question: null, recommendation: null, course: null, proposal: {
      title: 'Iceberg connector expert', objective: 'Explain and change the Iceberg connector', successDefinition: 'Trace a scan end to end',
      startingKnowledge: 'Knows Trino', constraints: 'Five hours a week' } });
    await nextRead();
    button('Adjust').click(); await settle();
    assert(composer().value === 'Adjust it: ' && document.activeElement === composer(), 'Adjust starts the reply in the field and focuses it');
    changeInput(composer(), ''); await settle();
    button('Skip').click(); await settle(); await settle();
    assert(asked() === 6 && server.exchanges.at(-1)?.message === 'Skip this proposal for now.', 'Skip tells the agent the user passes on the proposal');
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
    setCards([{ id: 'card-1', goalId: 'goal', card: CARD, state: 'SHOWN', taskId: null, workItemId: null, checkId: null, createdAt: NOW }]);
    await nextRead(); await settle();
    const card = document.querySelector('.goal-cards .goal-rec');
    assert(card !== null && card.textContent?.includes('Read the Iceberg specification') === true, 'The recommended research floats under the Goal control');
    assert(starts() === 0, 'Nothing starts until Start');
    [...card.querySelectorAll('button')].find((candidate) => candidate.textContent === 'Start')?.click(); await settle(); await settle();
    assert(starts() === 1 && document.querySelector('.goal-cards') === null, 'Start begins the run and the card leaves the control');
    assert(text().includes('Researching: Read the Iceberg specification'), 'The Goal window shows the running research');
    await nextRead();
    assert(text().includes('1 note saved') && !text().includes('Open run'), 'The running research shows its progress in the Goal window, not a task screen');
    button('Stop').click(); await settle(); await settle();
    assert(server.researchStopped && ![...document.querySelectorAll('.goal-guide__running button')].some((found) => found.textContent === 'Stop'),
      'Stop ends the research run, whose card then settles');

    // Later the agent judges the user ready to practice on a real gap.
    setCards([...server.goal.recommendations, { id: 'card-2', goalId: 'goal', card: PRACTICE, state: 'SHOWN', taskId: null, workItemId: null, checkId: null, createdAt: NOW }]);
    changed(); await settle();
    const practice = document.querySelector('.goal-cards .goal-rec');
    assert(practice !== null && practice.textContent?.includes('Practice') === true && practice.textContent.includes('Fix #31252'), 'A practice card floats with its kind');
    assert(practice.querySelector('.goal-rec__doubt')?.textContent === 'Pull request #31270 is open and linked to close issue #31252; it may already fix it.',
      'The card shows what is uncertain under its reason');
    [...practice.querySelectorAll('button')].find((candidate) => candidate.textContent === 'Start')?.click(); await settle(); await settle();
    assert(composers() === 1 && started[0].issue === 31252 && started[0].instruction.startsWith('Key checkpoint add and remove actions'),
      'Start opens the issue composer with the prepared instruction');
    assert(text().includes('Practicing: Fix #31252') && !text().includes('Researching: Fix #31252'), 'The started practice shows as practice, not research');
    button('Open the composer').click(); await settle();
    assert(composers() === 2 && started[1].issue === 31252, 'Until its task is sent, the practice leads back to the prepared composer');

    // The task the user sent published a pull request, which merged; accepting the result stays the user's decision.
    const link = (kind: GoalItemDto['kind'], key: string, label: string, status: string): GoalItemDto => ({ kind, key, label, title: 'Key actions by path',
      state: status === 'Merged' ? 'MERGED' : 'WORKING', status, blocked: false, url: null, activity: null, addedAt: NOW });
    server.goal = { ...server.goal, items: [{ id: 'work-1', workspaceId: 'workspace', goalId: 'goal', title: 'Fix #31252', kind: 'DEVELOP',
      acceptanceCriteria: PRACTICE.scope, completionRule: 'MERGED_DELIVERY', state: 'WORKING', revision: 2, priority: 'NORMAL', rank: 0, blockedBy: [], history: [],
      createdAt: NOW, updatedAt: NOW,
      links: [link('ISSUE', 'trinodb/trino#31252', '#31252', 'Open'), link('TASK', 'task-1', 'Task', 'Completed'), link('PULL_REQUEST', 'trinodb/trino#31260', '#31260', 'Merged')] }] };
    changed(); await settle();
    assert(text().includes('#31260 merged') && text().includes('Open task'), 'The practice shows its task and the merged pull request');
    assert(document.querySelector('.goal-pill__attention')?.textContent?.trim() === '1' && text().includes('Your decision: accept Fix #31252'),
      'The pill counts the merged result as a decision, and the window says it waits on the user');
    button('Review result').click(); await settle();
    assert(text().includes('Accept result') && text().includes('Fix #31252'), 'Review result opens the work, where the user accepts it');

    // The user cancels the goal and starts the next one by saying what they want.
    button('Cancel goal').click(); await settle();
    const confirmation = document.querySelector<HTMLInputElement>('dialog[open] input');
    assert(confirmation !== null && button('Confirm delete goal').disabled, 'Cancel goal requires typed confirmation');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(confirmation, 'cancel');
    confirmation.dispatchEvent(new Event('input', { bubbles: true })); await settle();
    button('Confirm delete goal').click();
    await settle(); await settle();
    assert(server.cancelled.join() === 'goal' && text().includes('What do you want to achieve?'), 'Cancel goal archives it and asks what the user wants next');
    assert(document.activeElement === composer(), 'The next question is ready for typing');
    changeInput(composer(), 'Review Iceberg pull requests with confidence'); await settle();
    button('Send').click(); await settle(); await settle();
    assert(server.goal?.title === 'Review Iceberg pull requests with confidence' && asked() === 7, 'The typed answer starts the next goal');

    // The agent recommends a spec check; Adjust opens the Spec check drawer with the card's choices instead of starting it.
    setCards([{ id: 'card-3', goalId: 'goal', card: CHECK, state: 'SHOWN', taskId: null, workItemId: null, checkId: null, createdAt: NOW }]);
    changed(); await settle();
    const check = document.querySelector('.goal-cards .goal-rec');
    assert(check !== null && check.textContent?.includes('Spec check') === true, 'A spec check floats with its kind');
    [...check.querySelectorAll('button')].find((candidate) => candidate.textContent === 'Adjust')?.click(); await settle();
    assert(specChecks.length === 1 && specChecks[0].directory === 'plugin/trino-delta-lake/' && specChecks[0].prefill?.reproductions === 3
      && specChecks[0].prefill.specs[0] === CHECK.pages[0] && starts() === 2, 'Adjust opens the drawer with the card’s choices and starts nothing');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
