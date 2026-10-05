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
  acceptanceCriteria: 'Explain metadata, snapshots and manifests', completionRule: 'ACCEPTED_RESULT', state: 'PLANNED', revision: 0, priority: 'NORMAL', rank: 0,
  blockedBy: [], links: [], history: [],
  createdAt: NOW, updatedAt: NOW };
const NEXT: WorkItemDto = { ...LESSON, id: 'lesson-2', title: 'Follow a snapshot', acceptanceCriteria: 'Trace a snapshot to its data files' };
const server = { reads: 0, opened: 0, finished: [] as string[], asked: [] as (string | null | undefined)[], messages: [] as string[] };
const WRITTEN: GoalLessonDto = { id: 'lesson-1', state: 'READY', failure: null, content: {
  summary: 'A table is a tree of metadata files.', invariants: ['A snapshot never changes once written.'], explanation: null,
  sections: [
    { title: 'Table metadata', body: 'The metadata file names the **current snapshot**.', diagram: { kind: 'TREE', caption: 'What a table points to', nodes: [
      { id: 'table', label: 'Table metadata', note: null, parent: null }, { id: 'snapshot', label: 'Snapshot', note: 'One per commit', parent: 'table' },
      { id: 'manifests', label: 'Manifest list', note: null, parent: 'snapshot' }] } },
    { title: 'Writing a snapshot', body: 'A commit swaps the metadata pointer.', diagram: { kind: 'FLOW', caption: 'A commit', nodes: [
      { id: 'write', label: 'Write data files', note: null, parent: null }, { id: 'swap', label: 'Swap the pointer', note: null, parent: null }] } }],
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
    revision: 1, course: { exchangeId: 'op-0', parts: [{ title: 'Iceberg tables', lessons: ['lesson-1', 'lesson-2'] }], acceptedAt: NOW }, items: [item, NEXT],
    recommendations: [], topics: [],
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
    researchRun: async () => ({ buildRequestSequence: 0, canStart: true, build: null }), stopResearch: async () => ({ buildRequestSequence: 0, canStart: true, build: null }),
    ask: async (operationId, message, lessonId) => {
      server.asked.push(lessonId);
      server.messages.push(message);
      setExchanges([{ id: operationId, goalId: 'goal', message, automatic: false, lessonId: lessonId ?? null, inputRevision: 1, state: 'ANSWERED', failure: null,
        askedAt: NOW, reply: { assessment: 'Because each snapshot shares unchanged manifests.', question: null, proposal: null, recommendation: null, course: null } }]);
    },
  };
  return (
    <div style={{ height: 800 }}>
      <GoalPanel mode="board" view="guide" onView={() => {}} lesson={lesson} onLesson={setLesson} conversation={conversation} onStart={async () => {}}
        onOpenTask={() => {}} repository="trinodb/trino" goals={[goal]} goal={goal} needsYou={new Set()} onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false}
        onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}} onSummarize={async () => {}} onDelete={async () => {}} onAdd={async () => {}} workItems={[item]} onCreateWork={async () => item} onDecide={async () => item}
        onOrder={async () => {}} onLink={async () => item} onOpenItem={() => {}} search={async () => []} />
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
    await until(() => text().includes('Writing the chapter'), 'Opening a lesson for the first time writes it');
    // Read through a function: an assertion narrows the server's fields for the rest of the run.
    const opened = (): number => server.opened;
    assert(opened() === 1, 'The lesson is opened once');
    await until(() => text().includes('A table is a tree of metadata files.'), 'The written chapter replaces the writing state');
    assert(document.querySelector('.goal-book__chapter[aria-current="page"]')?.textContent?.includes('What a table is') === true,
      'The expanded window reads as a book whose contents mark the open chapter');
    assert(document.querySelector('.goal-book__ask') === null, 'The questions open only on demand');
    assert(document.querySelector('.goal-chapter__remember li')?.textContent === 'A snapshot never changes once written.', 'The chapter lists what to remember');
    assert(document.querySelectorAll('.goal-diagram svg').length === 2 && document.querySelectorAll('.goal-diagram rect').length === 5
      && document.querySelectorAll('.goal-diagram__edge').length === 3, 'Each diagram draws its boxes and arrows');
    assert([...document.querySelectorAll('.goal-book__sections button')].map((section) => section.textContent).join('|') === 'Table metadata|Writing a snapshot',
      'The contents list the chapter\'s sections');
    assert(document.querySelector('.goal-chapter__section strong')?.textContent === 'current snapshot', 'A section reads as Markdown');

    changeInput(document.querySelector<HTMLTextAreaElement>('.goal-chapter__check .goal-composer textarea'), 'The manifest list of its table state'); await settle();
    button('Answer').click(); await settle();
    assert(server.asked[0] === 'lesson-1' && server.messages[0] === 'My answer to the check: The manifest list of its table state',
      'The check takes an answer, sent to the agent as the answer to the open lesson\'s check');
    assert(document.querySelector('.goal-book__ask .goal-guide') !== null, 'Answering the check opens the questions, where the agent answers');

    changeInput(document.querySelector<HTMLTextAreaElement>('.goal-book__ask .goal-composer textarea'), 'Why share manifests?'); await settle();
    button('Send').click(); await settle();
    assert(server.asked.length === 2 && server.asked[1] === 'lesson-1' && server.messages[1] === 'Why share manifests?',
      'A question asked beside the chapter belongs to it');
    await until(() => text().includes('Because each snapshot shares unchanged manifests.'), 'The answer shows beside the chapter');

    button('Mark lesson done').click(); await settle();
    await until(() => document.querySelector('.goal-lesson__done') === null, 'A finished lesson shows it is done');
    assert(server.finished.length === 1 && server.finished[0].startsWith('lesson-1:'), 'Done is recorded once for the lesson');
    assert(document.querySelector('.goal-book__chapter.is-done')?.textContent?.includes('What a table is') === true, 'The contents check the finished chapter');
    button('Next: Follow a snapshot →').click(); await settle();
    await until(() => document.querySelector('.goal-book__chapter[aria-current="page"]')?.textContent?.includes('Follow a snapshot') === true,
      'Next opens the following chapter');
    assert(opened() === 2, 'The next chapter is opened, and written, as it is first read');
    button('Back to guidance').click(); await settle();
    assert(document.querySelector('.goal-course__lessons li.is-done') !== null, 'The course shows the lesson done');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
