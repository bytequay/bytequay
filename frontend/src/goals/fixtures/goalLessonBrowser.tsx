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
import React, { useMemo, useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { GoalPanel } from '../GoalPanel';
import type { GoalDto, GoalExchangeDto, GoalLessonDto, WorkItemDto } from '../contracts';
import type { GoalConversation } from '../useGoals';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/goals.css';

const NOW = '2026-10-03T10:00:00Z';
const LESSON: WorkItemDto = { id: 'lesson-1', workspaceId: 'workspace', goalId: 'goal', title: 'What a table is', kind: 'LEARN',
  acceptanceCriteria: 'Explain metadata, snapshots and manifests', completionRule: 'ACCEPTED_RESULT', state: 'PLANNED', revision: 0, links: [], history: [],
  createdAt: NOW, updatedAt: NOW };
const server = { reads: 0, opened: 0, finished: [] as string[], asked: [] as (string | null | undefined)[] };
const WRITTEN: GoalLessonDto = { id: 'lesson-1', state: 'READY', failure: null, content: { explanation: '## Table metadata\nA table is a tree of metadata files.',
  exercise: 'Run `TestIcebergMetadata`.', observe: 'The test lists two snapshots.', check: 'What does a snapshot point to?' } };

function text(): string {
  return document.body.textContent ?? '';
}
function button(label: string): HTMLButtonElement {
  const found = [...document.querySelectorAll('button')].find((candidate) => candidate.textContent?.trim() === label);
  assert(found !== undefined, `Missing button ${label}`);
  return found;
}
async function until(condition: () => boolean, message: string): Promise<void> {
  for (let frame = 0; frame < 200 && !condition(); frame++) await settle();
  assert(condition(), message);
}

function Fixture(): React.JSX.Element {
  const [item, setItem] = useState(LESSON);
  const [lesson, setLesson] = useState<string | null>(null);
  const [exchanges, setExchanges] = useState<GoalExchangeDto[]>([]);
  const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Iceberg connector expert', description: '', pinned: true, paused: false, source: null, definition: null,
    revision: 1, course: { exchangeId: 'op-0', parts: [{ title: 'Iceberg tables', lessons: ['lesson-1'] }], acceptedAt: NOW }, items: [item], recommendations: [], topics: [],
    createdAt: NOW, updatedAt: NOW };
  // The app keeps these the same across renders, so an open lesson is not opened again.
  const lessons = useMemo<Pick<GoalConversation, 'openLesson' | 'readLesson' | 'finishLesson'>>(() => ({
    openLesson: async (id) => { server.opened++; return { id, state: 'WRITING', content: null, failure: null }; },
    // The lesson is written by the time the view reads it again.
    readLesson: async () => { server.reads++; return WRITTEN; },
    finishLesson: async (id, operationId) => { server.finished.push(`${id}:${operationId}`); setItem((current) => ({ ...current, state: 'DONE' })); },
  }), []);
  const conversation: GoalConversation = {
    ...lessons,
    loaded: true, exchanges, notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, stop: async () => {}, retry: async () => {}, accept: async () => {}, acceptCourse: async () => {}, correctTopic: async () => {},
    ask: async (operationId, message, lessonId) => {
      server.asked.push(lessonId);
      setExchanges([{ id: operationId, goalId: 'goal', message, automatic: false, lessonId: lessonId ?? null, inputRevision: 1, state: 'ANSWERED', failure: null,
        askedAt: NOW, reply: { assessment: 'Because each snapshot shares unchanged manifests.', question: null, proposal: null, recommendation: null, course: null } }]);
    },
  };
  return (
    <div style={{ height: 800 }}>
      <GoalPanel mode="board" view="guide" onView={() => {}} lesson={lesson} onLesson={setLesson} conversation={conversation} onStart={async () => {}}
        onOpenTask={() => {}} repository="trinodb/trino" goals={[goal]} goal={goal} needsYou={new Set()} onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false}
        onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}} onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} workItems={[item]} onCreateWork={async () => item} onDecide={async () => item}
        onLink={async () => item} onOpenItem={() => {}} search={async () => []} />
    </div>
  );
}

export async function run(): Promise<boolean> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<Fixture />)); await settle();
    button('What a table is').click(); await settle();
    await until(() => text().includes('Writing the lesson'), 'Opening a lesson for the first time writes it');
    assert(server.opened === 1, 'The lesson is opened once');
    await until(() => text().includes('A table is a tree of metadata files.'), 'The written lesson replaces the writing state');
    assert(document.querySelector('.goal-lesson__explanation h2')?.textContent === 'Table metadata', 'The explanation renders as Markdown');
    assert(text().includes('The test lists two snapshots.') && text().includes('What does a snapshot point to?'), 'The exercise and its check show');

    changeInput(document.querySelector<HTMLTextAreaElement>('.goal-composer textarea'), 'Why share manifests?'); await settle();
    button('Send').click(); await settle();
    assert(server.asked.length === 1 && server.asked[0] === 'lesson-1', 'A question asked in the lesson belongs to it');
    await until(() => text().includes('Because each snapshot shares unchanged manifests.'), 'The answer shows beside the lesson');

    button('Mark lesson done').click(); await settle();
    await until(() => document.querySelector('.goal-lesson__done') === null, 'A finished lesson shows it is done');
    assert(server.finished.length === 1 && server.finished[0].startsWith('lesson-1:'), 'Done is recorded once for the lesson');
    button('Back to guidance').click(); await settle();
    assert(document.querySelector('.goal-course__lessons li.is-done') !== null, 'The course shows the lesson done');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
