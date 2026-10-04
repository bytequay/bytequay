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
import type { GoalDto, WorkItemDto, WorkItemDecision } from '../contracts';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/goals.css';

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
    acceptanceCriteria: 'Explain the contribution path', completionRule: 'ACCEPTED_RESULT', state: 'WORKING', revision: 0, links: [], history: [], createdAt: now, updatedAt: now };
  const decisions: WorkItemDecision[] = [];
  let rejectFirst = true;
  function Fixture(): React.JSX.Element {
    const [item, setItem] = useState(initial);
    const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'First contribution', description: '', pinned: true, paused: false, source: null,
      definition: null, revision: 0, course: null, items: item.goalId === 'goal' ? [item] : [], recommendations: [], topics: [], createdAt: now, updatedAt: now };
    return <GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {} }}
      repository="org/repo" goal={goal} goals={[goal]} workItems={[item]} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}} onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => item} onLink={async () => item}
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
    completionRule: 'MERGED_DELIVERY', state: 'DONE', revision: 3, links: [], history: [accepted], createdAt: now, updatedAt: now };
  const asked: string[] = [];
  let show: (item: WorkItemDto) => void = () => {};
  function Fixture(): React.JSX.Element {
    const [item, setItem] = useState(finished);
    show = setItem;
    const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Delta Lake correctness', description: '', pinned: true, paused: false, source: null,
      definition: null, revision: 0, course: null, items: [item], recommendations: [], topics: [], createdAt: now, updatedAt: now };
    return <GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {} }}
      repository="org/repo" goal={goal} goals={[goal]} workItems={[item]} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}}
      onSummarize={async (summarized) => { asked.push(summarized.id); }} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => item} onLink={async () => item} onDecide={async () => item} />;
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
  const link = (kind: 'ISSUE' | 'TASK', key: string, state: 'IDLE' | 'ENDED'): WorkItemDto['links'][number] => ({ kind, key, label: kind === 'ISSUE' ? '#31252' : 'Task',
    title: 'Fix the checkpoint', state, status: state === 'ENDED' ? 'Failed' : 'Idle', blocked: false, url: null, activity: null, addedAt: now });
  const work = (task: 'IDLE' | 'ENDED'): WorkItemDto => ({ id: 'work', workspaceId: 'workspace', goalId: 'goal', title: 'Fix #31252', kind: 'DEVELOP',
    acceptanceCriteria: 'Key actions by path and deletion vector.', completionRule: 'MERGED_DELIVERY', state: 'WORKING', revision: 2,
    links: [link('ISSUE', 'trinodb/trino#31252', 'IDLE'), link('TASK', 'task-1', task)], history: [], createdAt: now, updatedAt: now });
  const started: [number, string, string | undefined][] = [];
  let show: (item: WorkItemDto) => void = () => {};
  function Fixture(): React.JSX.Element {
    const [item, setItem] = useState(work('IDLE'));
    show = setItem;
    const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Delta Lake correctness', description: '', pinned: true, paused: false, source: null,
      definition: null, revision: 0, course: null, items: [item], recommendations: [], topics: [], createdAt: now, updatedAt: now };
    return <GoalPanel mode="window" view="work" onView={() => {}} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      conversation={{ loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, ask: async () => {}, retry: async () => {}, stop: async () => {}, accept: async () => {}, acceptCourse: async () => {},
        openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {}, correctTopic: async () => {} }}
      repository="trinodb/trino" goal={goal} goals={[goal]} workItems={[item]} needsYou={new Set()}
      onClose={() => {}} onSwitch={() => {}} onStartIssue={(issue, instruction, replaces) => started.push([issue, instruction, replaces])} onStartTicket={() => {}}
      onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}}
      onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
      search={async () => []} onCreateWork={async () => item} onLink={async () => item} onDecide={async () => item} />;
  }
  try {
    flushSync(() => root.render(<Fixture />)); await settle();
    const card = host.querySelector<HTMLButtonElement>('.goal-work-card'); assert(card !== null, 'Work item card missing'); card.click(); await settle();
    assert(![...host.querySelectorAll('button')].some((found) => found.textContent === 'Start another attempt'), 'An open task is still the attempt');
    flushSync(() => show(work('ENDED'))); await settle();
    button(host, 'Start another attempt').click(); await settle();
    assert(started.length === 1 && started[0][0] === 31252 && started[0][1] === 'Key actions by path and deletion vector.' && started[0][2] === 'task-1',
      'Another attempt opens the issue composer over the ended task');
  }
  finally { root.unmount(); host.remove(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of [['Accept, retry, reopen and detach retain decisions', decisionsSurviveFailure],
    ['Finished work keeps its summary', finishedWorkKeepsItsSummary], ['An ended task offers another attempt', anotherAttemptAfterAnEndedTask]] as const) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: String(error) }); }
  }
  return results;
}
