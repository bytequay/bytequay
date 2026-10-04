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
      onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}} onDelete={async () => {}} onAdd={async () => {}} onOpenItem={() => {}}
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

export async function run(): Promise<{ name: string; error?: string }[]> {
  try { await decisionsSurviveFailure(); return [{ name: 'Accept, retry, reopen and detach retain decisions' }]; }
  catch (error) { return [{ name: 'Accept, retry, reopen and detach retain decisions', error: String(error) }]; }
}
