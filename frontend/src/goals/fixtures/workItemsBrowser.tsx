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

import React, { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { GoalPanel } from '../GoalPanel';
import type { ContextBuildViewDto } from '../../repository-context/contracts';
import type { GoalDto, WorkItemDto, WorkItemDecision } from '../contracts';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/goals.css';

const IDLE_RUN: ContextBuildViewDto = { buildRequestSequence: 0, canStart: true, build: null };

function button(host: HTMLElement, label: string): HTMLButtonElement {
  const found = [...host.querySelectorAll('button')].find((candidate) => candidate.textContent?.trim() === label);
  assert(found !== undefined, `Missing button: ${label}`);
  return found;
}

async function decisionsSurviveFailure(): Promise<void> {
  const host = document.createElement('div'); document.body.append(host);
  host.style.width = '380px';
  const root = createRoot(host);
  const now = '2026-09-01T00:00:00Z';
  const initial: WorkItemDto = { id: 'work', workspaceId: 'workspace', goalId: 'goal', title: 'Learn the contribution flow', kind: 'LEARN',
    acceptanceCriteria: 'Explain the contribution path', completionRule: 'ACCEPTED_RESULT', state: 'WORKING', revision: 0, links: [], history: [], createdAt: now, updatedAt: now,
    priority: 'NORMAL', rank: 0, blockedBy: [] };
  const decisions: WorkItemDecision[] = [];
  let rejectFirst = true;
  function Fixture(): React.JSX.Element {
    const [item, setItem] = useState(initial);
    const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'First contribution', description: '', pinned: true, paused: false, source: null,
      definition: null, revision: 0, course: null, items: item.goalId === 'goal' ? [item] : [], recommendations: [], topics: [], createdAt: now, updatedAt: now };
    return <GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptPlan: async () => { throw new Error('No plan change'); }, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {}, researchRun: async () => IDLE_RUN, stopResearch: async () => IDLE_RUN }}
      repository="org/repo" goal={goal} goals={[goal]} workItems={[item]} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}} onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => item} onOrder={async () => {}} onLink={async () => item}
      onDecide={async (_item, command) => {
        decisions.push(command);
        if (rejectFirst) { rejectFirst = false; throw new Error('Connection interrupted'); }
        const updated: WorkItemDto = { ...item, state: command.action === 'ACCEPT' ? 'DONE' : command.action === 'REOPEN' ? 'WORKING' : item.state,
          revision: item.revision + 1, goalId: command.action === 'MOVE' ? command.goalId ?? null : item.goalId,
          history: [...item.history, { operationId: command.operationId, action: command.action, detail: command.detail, at: now }] };
        setItem(updated); return updated;
      }} />;
  }
  try {
    flushSync(() => root.render(<Fixture />)); await settle();
    const card = host.querySelector<HTMLButtonElement>('.goal-work-card'); assert(card !== null, 'Work item card missing'); card.click(); await settle();
    let result = host.querySelectorAll('textarea')[1];
    assert(result !== undefined, 'Result editor missing');
    changeInput(result, 'Reviewed the saved contribution notes'); await settle();
    button(host, 'Accept result').click(); await settle();
    assert(host.textContent?.includes('Connection interrupted') === true, 'Failed save must be visible');
    assert(result.value === 'Reviewed the saved contribution notes', 'Failure erased the result draft');
    button(host, 'Accept result').click(); await settle();
    assert(decisions.length === 2 && decisions[0].operationId === decisions[1].operationId, 'Retry changed its decision identity');
    assert(host.textContent?.includes('Reviewed the saved contribution notes') === true, 'Accepted result missing from history');
    result = host.querySelectorAll('textarea')[1]; changeInput(result, 'Cover one more path'); await settle();
    button(host, 'Reopen').click(); await settle();
    assert(host.textContent?.includes('Cover one more path') === true, 'Reopen lost history');
    const selects = host.querySelectorAll('select');
    const goal = selects[selects.length - 1];
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(goal, '');
    goal.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    button(host, 'Move work item').click(); await settle();
    button(host, 'Add existing').click(); await settle();
    button(host, 'Learn the contribution flow').click(); await settle();
    assert(host.textContent?.includes('Reviewed the saved contribution notes') === true, 'Detached work lost its readable history');
  }
  finally { root.unmount(); host.remove(); }
}

async function finishedWorkKeepsItsSummary(): Promise<void> {
  const host = document.createElement('div'); document.body.append(host);
  host.style.width = '380px';
  const root = createRoot(host);
  const now = '2026-09-01T00:00:00Z';
  const accepted = { operationId: 'op-9', action: 'ACCEPT', detail: 'User accepted all criteria: Rows survive', at: now };
  const finished: WorkItemDto = { id: 'work', workspaceId: 'workspace', goalId: 'goal', title: 'Fix #31252', kind: 'DEVELOP', acceptanceCriteria: 'Rows survive',
    completionRule: 'MERGED_DELIVERY', state: 'DONE', revision: 3, links: [], history: [accepted], createdAt: now, updatedAt: now, priority: 'NORMAL', rank: 0, blockedBy: [] };
  const asked: string[] = [];
  let show: (item: WorkItemDto) => void = () => {};
  function Fixture(): React.JSX.Element {
    const [item, setItem] = useState(finished);
    show = setItem;
    const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Delta Lake correctness', description: '', pinned: true, paused: false, source: null,
      definition: null, revision: 0, course: null, items: [item], recommendations: [], topics: [], createdAt: now, updatedAt: now };
    return <GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptPlan: async () => { throw new Error('No plan change'); }, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {}, researchRun: async () => IDLE_RUN, stopResearch: async () => IDLE_RUN }}
      repository="org/repo" goal={goal} goals={[goal]} workItems={[item]} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}}
      onSummarize={async (summarized) => { asked.push(summarized.id); }} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => item} onOrder={async () => {}} onLink={async () => item} onDecide={async () => item} />;
  }
  try {
    flushSync(() => root.render(<Fixture />)); await settle();
    const card = host.querySelector<HTMLButtonElement>('.goal-work-card'); assert(card !== null, 'Work item card missing'); card.click(); await settle();
    assert(host.textContent?.includes('Summary pending.') === true, 'Finished work without its summary must say it is pending');
    button(host, 'Summarize').click(); await settle();
    assert(asked.join() === 'work' && host.textContent?.includes('Writing the summary…') === true, 'Summarize must ask once and say it is writing');
    flushSync(() => show({ ...finished, history: [accepted, { operationId: 'summary:op-9', action: 'SUMMARY', detail: '**What changed** Actions keyed by path', at: now }] }));
    await settle();
    const summary = host.querySelector('.goal-work-summary');
    assert(summary?.querySelector('strong')?.textContent === 'What changed' && summary.textContent?.includes('Actions keyed by path') === true,
      'The saved summary must render as Markdown');
    assert(host.textContent?.split('Actions keyed by path').length === 2, 'The summary must not repeat in the history');
  }
  finally { root.unmount(); host.remove(); }
}

async function anotherAttemptAfterAnEndedTask(): Promise<void> {
  const host = document.createElement('div'); document.body.append(host);
  host.style.width = '380px';
  const root = createRoot(host);
  const now = '2026-09-01T00:00:00Z';
  const link = (kind: 'ISSUE' | 'JIRA' | 'TASK', key: string, state: 'IDLE' | 'ENDED'): WorkItemDto['links'][number] => ({ kind, key, label: kind === 'TASK' ? 'Task' : key,
    title: 'Fix the checkpoint', state, status: state === 'ENDED' ? 'Failed' : 'Idle', blocked: false, url: null, activity: null, addedAt: now });
  const work = (task: 'IDLE' | 'ENDED', source = link('ISSUE', 'trinodb/trino#31252', 'IDLE')): WorkItemDto => ({ id: 'work', workspaceId: 'workspace', goalId: 'goal',
    title: 'Fix #31252', kind: 'DEVELOP',
    acceptanceCriteria: 'Key actions by path and deletion vector.', completionRule: 'MERGED_DELIVERY', state: 'WORKING', revision: 2, priority: 'NORMAL', rank: 0, blockedBy: [],
    links: [source, link('TASK', 'task-1', task)], history: [], createdAt: now, updatedAt: now });
  const started: [number, string, string | undefined][] = [];
  const tickets: [string, string, string | undefined][] = [];
  let show: (item: WorkItemDto) => void = () => {};
  function Fixture(): React.JSX.Element {
    const [item, setItem] = useState(work('IDLE'));
    show = setItem;
    const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Delta Lake correctness', description: '', pinned: true, paused: false, source: null,
      definition: null, revision: 0, course: null, items: [item], recommendations: [], topics: [], createdAt: now, updatedAt: now };
    return <GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptPlan: async () => { throw new Error('No plan change'); }, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {}, researchRun: async () => IDLE_RUN, stopResearch: async () => IDLE_RUN }}
      repository="trinodb/trino" goal={goal} goals={[goal]} workItems={[item]} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={(issue, instruction, replaces) => started.push([issue, instruction, replaces])}
      onStartTicket={(ticket, instruction, replaces) => tickets.push([ticket, instruction, replaces])}
      onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}}
      onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => item} onOrder={async () => {}} onLink={async () => item} onDecide={async () => item} />;
  }
  try {
    flushSync(() => root.render(<Fixture />)); await settle();
    const card = host.querySelector<HTMLButtonElement>('.goal-work-card'); assert(card !== null, 'Work item card missing'); card.click(); await settle();
    assert(![...host.querySelectorAll('button')].some((found) => found.textContent === 'Start another attempt'), 'An open task is still the attempt');
    flushSync(() => show(work('ENDED'))); await settle();
    button(host, 'Start another attempt').click(); await settle();
    assert(started.length === 1 && started[0][0] === 31252 && started[0][1] === 'Key actions by path and deletion vector.' && started[0][2] === 'task-1',
      'Another attempt opens the issue composer over the ended task');
    flushSync(() => show(work('ENDED', link('JIRA', 'connection:cloud:10001', 'IDLE')))); await settle();
    button(host, 'Start another attempt').click(); await settle();
    assert(started.length === 1 && tickets.length === 1 && tickets[0][0] === 'connection:cloud:10001' && tickets[0][2] === 'task-1',
      'Another attempt on a ticket opens the ticket composer over the ended task');
  }
  finally { root.unmount(); host.remove(); }
}

function choose(select: HTMLSelectElement, value: string): void {
  Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(select, value);
  select.dispatchEvent(new Event('change', { bubbles: true }));
}

async function orderAndBlockers(): Promise<void> {
  const host = document.createElement('div'); document.body.append(host);
  host.style.width = '380px';
  const root = createRoot(host);
  const now = '2026-09-01T00:00:00Z';
  const work = (id: string, title: string, rank: number, blockedBy: string[]): WorkItemDto => ({ id, workspaceId: 'workspace', goalId: 'goal', title, kind: 'DEVELOP',
    acceptanceCriteria: 'Rows survive a checkpoint', completionRule: 'ACCEPTED_RESULT', state: 'PLANNED', revision: 1, links: [], history: [], createdAt: now, updatedAt: now,
    priority: 'NORMAL', rank, blockedBy });
  const decisions: WorkItemDecision[] = [];
  const orders: string[][] = [];
  function Fixture(): React.JSX.Element {
    const [items, setItems] = useState([work('read', 'Read the checkpoint code', 0, []), work('fix', 'Fix the checkpoint', 1, ['read'])]);
    const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Delta Lake correctness', description: '', pinned: true, paused: false, source: null,
      definition: null, revision: 0, course: null, items, recommendations: [], topics: [], createdAt: now, updatedAt: now };
    return <GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptPlan: async () => { throw new Error('No plan change'); }, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {}, researchRun: async () => IDLE_RUN, stopResearch: async () => IDLE_RUN }}
      repository="trinodb/trino" goal={goal} goals={[goal]} workItems={items} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}}
      onRename={async () => {}} onPause={async () => {}} onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => items[0]} onLink={async () => items[0]}
      onOrder={async (_goalId, ids) => { orders.push(ids); setItems((current) => current.map((item) => ({ ...item, rank: ids.indexOf(item.id) }))); }}
      onDecide={async (item, command) => {
        decisions.push(command);
        const updated: WorkItemDto = { ...item, revision: item.revision + 1, priority: command.priority ?? item.priority,
          blockedBy: command.action === 'UNBLOCK' ? item.blockedBy.filter((id) => id !== command.blocker) : item.blockedBy };
        setItems((current) => current.map((existing) => existing.id === item.id ? updated : existing));
        return updated;
      }} />;
  }
  try {
    flushSync(() => root.render(<Fixture />)); await settle();
    const open = async (title: string): Promise<HTMLElement> => {
      const card = [...host.querySelectorAll<HTMLButtonElement>('.goal-work-card')].find((found) => found.textContent?.includes(title));
      assert(card !== undefined, `Work item card missing: ${title}`); card.click(); await settle();
      const order = host.querySelector<HTMLElement>('section[aria-label="Order"]'); assert(order !== null, 'Order section missing');
      return order;
    };
    const select = (order: HTMLElement, label: string): HTMLSelectElement => {
      const found = [...order.querySelectorAll('label')].find((candidate) => candidate.textContent?.startsWith(label))?.querySelector('select');
      assert(found !== null && found !== undefined, `Missing select: ${label}`);
      return found;
    };
    const blocker = await open('Read the checkpoint code');
    assert(blocker.textContent?.includes('BlocksFix the checkpoint') === true, 'The blocker names the work waiting on it');
    const reason = [...host.querySelectorAll('label')].find((candidate) => candidate.textContent?.startsWith('Result, evidence'))?.querySelector('textarea');
    assert(reason !== null && reason !== undefined, 'Reason field missing'); changeInput(reason, 'Not needed'); await settle();
    button(host, 'Drop and unblock 1').click(); await settle();
    assert(decisions.at(-1)?.action === 'DROP' && decisions.at(-1)?.releaseDependents === true, 'Dropping a blocker is the decision to stop the waiting');
    button(host, 'Back to work').click(); await settle();

    const blocked = [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find((candidate) => candidate.textContent?.startsWith('Blocked'));
    assert(blocked !== undefined, 'Blocked tab missing'); blocked.click(); await settle();
    const order = await open('Fix the checkpoint');
    assert(order.textContent?.includes('Waits onRead the checkpoint code') === true, 'The details name what the work waits on');
    choose(select(order, 'Priority'), 'HIGH'); await settle();
    assert(decisions.at(-1)?.action === 'PRIORITIZE' && decisions.at(-1)?.priority === 'HIGH', 'Choosing High saves the priority');
    button(host, 'Move earlier').click(); await settle();
    assert(JSON.stringify(orders) === JSON.stringify([['fix', 'read']]), 'Move earlier saves the whole order with the work moved');
    button(host, 'Stop waiting').click(); await settle();
    assert(decisions.at(-1)?.action === 'UNBLOCK' && decisions.at(-1)?.blocker === 'read', 'Stop waiting removes the blocker');
    choose(select(order, 'Waits on'), 'read'); await settle();
    assert(decisions.at(-1)?.action === 'BLOCK' && decisions.at(-1)?.blocker === 'read', 'Choosing work makes this work wait on it');
  }
  finally { root.unmount(); host.remove(); }
}

async function viewsPutReadyWorkFirst(): Promise<void> {
  const host = document.createElement('div'); document.body.append(host);
  host.style.width = '380px';
  const root = createRoot(host);
  const now = '2026-09-01T00:00:00Z';
  const work = (id: string, rank: number, extra: Partial<WorkItemDto> = {}): WorkItemDto => ({ id, workspaceId: 'workspace', goalId: 'goal', title: `Work ${id}`,
    kind: 'DEVELOP', acceptanceCriteria: 'Done', completionRule: 'ACCEPTED_RESULT', state: 'PLANNED', revision: 1, links: [], history: [], createdAt: now, updatedAt: now,
    priority: 'NORMAL', rank, blockedBy: [], ...extra });
  const ticket: WorkItemDto['links'][number] = { kind: 'JIRA', key: 'connection:cloud:10412', label: 'TRINO-412', title: 'Keep deletion vectors', state: 'IDLE',
    status: 'To Do', blocked: false, url: null, activity: null, addedAt: now, priority: 'Highest' };
  const items = [work('a', 0), work('b', 1), work('c', 2, { priority: 'HIGH', links: [ticket] }), work('d', 3, { blockedBy: ['b'] }), work('f', 4, { state: 'WORKING' }),
    work('g', 5, { state: 'DONE' })];
  const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Delta Lake correctness', description: '', pinned: true, paused: false, source: null,
    definition: null, revision: 0, course: null, items, recommendations: [], topics: [], createdAt: now, updatedAt: now };
  const cards = (): string[] => [...host.querySelectorAll('.goal-work-card .goal-work-card__title')].map((title) => title.textContent ?? '');
  try {
    flushSync(() => root.render(<GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptPlan: async () => { throw new Error('No plan change'); }, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {}, researchRun: async () => IDLE_RUN, stopResearch: async () => IDLE_RUN }}
      repository="trinodb/trino" goal={goal} goals={[goal]} workItems={items} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}}
      onRename={async () => {}} onPause={async () => {}} onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => items[0]} onLink={async () => items[0]} onOrder={async () => {}} onDecide={async () => items[0]} />));
    await settle();
    assert(JSON.stringify(cards()) === JSON.stringify(['Work c', 'Work b', 'Work a']), `Next puts priority, then unblocking work, then order first: ${cards().join(', ')}`);
    const first = host.querySelector('.goal-work-card');
    assert(first?.textContent?.includes('High') === true && first.textContent.includes('TRINO-412 Highest'), 'A card shows the user priority and the source priority apart');
    assert(host.querySelectorAll('.goal-work-card')[1]?.textContent?.includes('Unblocks 1') === true, 'A card says what waits on it');
    const tab = (label: string): HTMLButtonElement => {
      const found = [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find((candidate) => candidate.textContent?.startsWith(label));
      assert(found !== undefined, `Missing tab: ${label}`);
      return found;
    };
    assert(tab('Blocked').textContent === 'Blocked 1' && tab('Working').textContent === 'Working 1' && tab('Done').textContent === 'Done 1', 'Each view counts its work');
    tab('Blocked').click(); await settle();
    assert(JSON.stringify(cards()) === JSON.stringify(['Work d']) && host.textContent?.includes('Waits on Work b') === true, 'Blocked says what the work waits on');
    tab('Next').click(); await settle();
    const priority = host.querySelector<HTMLSelectElement>('select[aria-label="Priority"]'); assert(priority !== null, 'Priority filter missing');
    choose(priority, 'HIGH'); await settle();
    assert(JSON.stringify(cards()) === JSON.stringify(['Work c']), 'The priority filter narrows the view');
    choose(priority, 'ANY');
    const source = host.querySelector<HTMLSelectElement>('select[aria-label="Source"]'); assert(source !== null, 'Source filter missing');
    choose(source, 'none'); await settle();
    assert(JSON.stringify(cards()) === JSON.stringify(['Work b', 'Work a']), 'The source filter narrows the view');
  }
  finally { root.unmount(); host.remove(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of [['Accept, retry, reopen and detach retain decisions', decisionsSurviveFailure],
    ['Finished work keeps its summary', finishedWorkKeepsItsSummary], ['An ended task offers another attempt', anotherAttemptAfterAnEndedTask],
    ['Priority, order and blockers are decisions on the work', orderAndBlockers], ['Views put ready work first', viewsPutReadyWorkFirst]] as const) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: String(error) }); }
  }
  return results;
}
