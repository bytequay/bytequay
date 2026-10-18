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
import { GoalPanel, type GoalPanelView } from '../GoalPanel';
import type { GoalDto, GoalExchangeDto, GoalLessonDto, LessonDiagramDto, WorkItemDto } from '../contracts';
import type { GoalConversation } from '../useGoals';
import { assert, changeInput, settle, until } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/goals.css';

const NOW = '2026-10-03T10:00:00Z';
const LESSON: WorkItemDto = { id: 'lesson-1', workspaceId: 'workspace', isEpic: false, contextLinks: [], goalId: 'goal', title: 'What a table is', kind: 'LEARN',
  acceptanceCriteria: 'Explain metadata, snapshots and manifests', completionRule: 'ACCEPTED_RESULT', state: 'PLANNED', revision: 0, priority: 'NORMAL', rank: 0,
  blockedBy: [], links: [], history: [],
  createdAt: NOW, updatedAt: NOW };
const NEXT: WorkItemDto = { ...LESSON, id: 'lesson-2', title: 'Follow a snapshot', acceptanceCriteria: 'Trace a snapshot to its data files' };
const server = { reads: 0, opened: 0, again: 0, finished: [] as string[], asked: [] as (string | null | undefined)[], messages: [] as string[] };
const WRITTEN: GoalLessonDto = { id: 'lesson-1', state: 'READY', failure: null, content: {
  summary: 'A table is a tree of metadata files.', invariants: ['A snapshot never changes once written.'], explanation: null,
  sections: [
    { title: 'Table metadata', body: 'The metadata file names the **current snapshot**.', diagram: { kind: 'TREE', caption: 'What a table points to', nodes: [
      { id: 'table', label: 'Table metadata', note: null, parent: null, group: null }, { id: 'snapshot', label: 'Snapshot', note: 'One per commit', parent: 'table', group: null },
      { id: 'manifests', label: 'Manifest list', note: null, parent: 'snapshot', group: null }], edges: [] } },
    { title: 'Writing a snapshot', body: 'A commit swaps the metadata pointer.', diagram: { kind: 'FLOW', caption: 'A commit', nodes: [
      { id: 'write', label: 'Write data files', note: null, parent: null, group: null }, { id: 'swap', label: 'Swap the pointer', note: null, parent: null, group: null }], edges: [] } },
    // Five participants draw wider than the chapter, which must scale the picture down rather than widen.
    { title: 'Who reads a snapshot', body: 'Planning reads the current snapshot once.\n\n| Step | Code |\n| --- | --- |\n| Plan | '
      + '`IcebergMetadata.getTableHandle -> IcebergSplitManager.getSplits -> IcebergSplitSource.getNextBatch -> TableScan.planFiles` |',
    diagram: { kind: 'SEQUENCE', caption: 'Planning a scan', nodes: [
      { id: 'engine', label: 'Engine', note: null, parent: null, group: null }, { id: 'connector', label: 'Connector', note: null, parent: null, group: null },
      { id: 'catalog', label: 'Catalog', note: null, parent: null, group: null }, { id: 'table', label: 'Table metadata', note: null, parent: null, group: null },
      { id: 'files', label: 'Manifest files', note: null, parent: null, group: null }], edges: [{ from: 'engine', to: 'connector', label: 'plan' },
      { from: 'connector', to: 'catalog', label: 'load table' }, { from: 'catalog', to: 'table', label: 'current snapshot' }, { from: 'table', to: 'files', label: 'list' }] } }],
  // A command too long to wrap must scroll inside the chapter instead of widening it.
  exercise: 'Run the metadata test:\n\n```bash\n./mvnw -pl plugin/trino-iceberg -Dtest=TestIcebergMetadata#testSnapshotsListedInOrder '
    + '-Dair.check.skip-all=true -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.javadoc.skip=true -Dlicense.skip=true test\n```',
  observe: 'The test lists **two snapshots**.\n\n| Snapshot | State |\n| --- | --- |\n| First | `committed` |\n| Second | `current` |\n\nCompare with #123.',
  check: 'What does a snapshot point to?' } };

function text(): string {
  return document.body.textContent ?? '';
}
function button(label: string): HTMLButtonElement {
  const found = [...document.querySelectorAll('button')].find((candidate) => candidate.textContent?.trim() === label);
  assert(found !== undefined, `Missing button ${label}`);
  return found;
}

const COURSE_MAP: LessonDiagramDto = { kind: 'COMPONENTS', caption: 'The concepts of an Iceberg table', nodes: [
  { id: 'table', label: 'TableMetadata', note: 'The current state', parent: null, group: 'Metadata' },
  { id: 'snapshot', label: 'Snapshot', note: null, parent: null, group: 'Metadata' },
  { id: 'scan', label: 'TableScan', note: 'Plans the files', parent: null, group: 'Engine' }],
edges: [{ from: 'table', to: 'snapshot', label: 'points to' }, { from: 'scan', to: 'snapshot', label: 'reads' }] };

function Fixture(): React.JSX.Element {
  const [item, setItem] = useState(LESSON);
  const [lesson, setLesson] = useState<string | null>(null);
  const [view, setView] = useState<GoalPanelView>('guide');
  const [exchanges, setExchanges] = useState<GoalExchangeDto[]>([]);
  const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Iceberg connector expert', description: '', pinned: true, paused: false, source: null, definition: null,
    revision: 1, course: { exchangeId: 'op-0', parts: [{ title: 'Iceberg tables', lessons: ['lesson-1', 'lesson-2'] }], acceptedAt: NOW, map: COURSE_MAP },
    items: [item, NEXT],
    recommendations: [], topics: [],
    createdAt: NOW, updatedAt: NOW };
  // The app keeps these the same across renders, so an open lesson is not opened again.
  const lessons = useMemo<Pick<GoalConversation, 'openLesson' | 'readLesson' | 'finishLesson'>>(() => ({
    openLesson: async (id, again) => { server.opened++; if (again === true) server.again++; return { id, state: 'WRITING', content: null, failure: null }; },
    // The lesson is written by the time the view reads it again.
    readLesson: async () => { server.reads++; return WRITTEN; },
    finishLesson: async (id, operationId) => { server.finished.push(`${id}:${operationId}`); setItem((current) => ({ ...current, state: 'DONE' })); },
  }), []);
  const conversation: GoalConversation = {
    ...lessons,
    loaded: true, exchanges, notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, reassess: async () => {}, stop: async () => {}, retry: async () => {}, accept: async () => {}, acceptPlan: async () => { throw new Error('No plan change'); }, acceptCourse: async () => {}, correctTopic: async () => {},
    researchRun: async () => ({ buildRequestSequence: 0, canStart: true, build: null }), stopResearch: async () => ({ buildRequestSequence: 0, canStart: true, build: null }),
    ask: async (operationId, message, lessonId) => {
      server.asked.push(lessonId);
      server.messages.push(message);
      setExchanges([{ id: operationId, goalId: 'goal', message, automatic: false, lessonId: lessonId ?? null, inputRevision: 1, state: 'ANSWERED', failure: null,
        askedAt: NOW, reply: { assessment: 'Because each snapshot shares unchanged manifests.', question: null, proposal: null, plan: null, recommendation: null, course: null } }]);
    },
  };
  return (
    <div style={{ height: 800 }}>
      <GoalPanel mode="board" view={view} onView={setView} lesson={lesson} onLesson={setLesson} conversation={conversation} onStart={async () => {}}
        onOpenTask={() => {}} repository="trinodb/trino" goals={[goal]} goal={goal} needsYou={new Set()} onClose={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onOpenGap={async () => {}}
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
    assert(button('Lessons') !== undefined, 'A goal with a course has a Lessons tab');
    button('What a table is').click(); await settle();
    assert(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent === 'Lessons', 'Opening a chapter from the guide reads it under Lessons');
    await until(() => text().includes('Writing the chapter'), 'Opening a lesson for the first time writes it');
    // Read through a function: an assertion narrows the server's fields for the rest of the run.
    const opened = (): number => server.opened;
    assert(opened() === 1, 'The lesson is opened once');
    await until(() => text().includes('A table is a tree of metadata files.'), 'The written chapter replaces the writing state');
    assert(document.querySelector('.goal-book__chapter[aria-current="page"]')?.textContent?.includes('What a table is') === true,
      'The expanded window reads as a book whose contents mark the open chapter');
    assert(document.querySelector('.goal-book__ask') === null, 'The questions open only on demand');
    assert(document.querySelector('.goal-chapter__remember li')?.textContent === 'A snapshot never changes once written.', 'The chapter lists what to remember');
    assert(document.querySelectorAll('.goal-diagram > svg').length === 3 && document.querySelectorAll('.goal-diagram rect').length === 10
      && document.querySelectorAll('.goal-diagram__edge').length === 7, 'Each diagram draws its boxes and arrows');
    assert([...document.querySelectorAll('.goal-book__sections button')].map((section) => section.textContent).join('|')
      === 'Table metadata|Writing a snapshot|Who reads a snapshot', 'The contents list the chapter\'s sections');
    const page = document.querySelector('.goal-book__page')?.getBoundingClientRect();
    const chapter = document.querySelector('.goal-chapter')?.getBoundingClientRect();
    assert(page !== undefined && chapter !== undefined && chapter.width <= 840 && Math.abs((chapter.left - page.left) - (page.right - chapter.right)) < 24
      && [...document.querySelectorAll('.goal-chapter > *')].every((part) => part.getBoundingClientRect().right <= chapter.right + 1),
      'A long command or a wide diagram stays inside the chapter, which sits in the middle of the page');
    assert(document.querySelector('.goal-chapter__section strong')?.textContent === 'current snapshot', 'A section reads as Markdown');
    const observations = document.querySelector('.goal-chapter__try > .markdown-body:last-child');
    assert(observations?.querySelector('strong')?.textContent === 'two snapshots'
      && observations.querySelectorAll('thead th').length === 2 && observations.querySelectorAll('tbody tr').length === 2
      && observations.querySelector('tbody td code')?.textContent === 'committed'
      && observations.querySelector('a')?.getAttribute('href') === 'https://github.com/trinodb/trino/issues/123',
    'What you should see renders Markdown tables and inline formatting, preserving repository issue links');

    const expand = document.querySelector<HTMLButtonElement>('.goal-diagram__expand');
    assert(expand !== null, 'Each diagram can be expanded');
    expand.focus(); expand.click(); await settle();
    const viewer = document.querySelector<HTMLDialogElement>('.goal-diagram__viewer');
    assert(viewer?.open === true, 'Expand opens a modal viewer');
    const picture = viewer.querySelector<SVGSVGElement>('.goal-diagram__canvas > svg');
    assert(picture !== null && picture.getBoundingClientRect().width === picture.viewBox.baseVal.width, 'The viewer starts at readable actual size');
    viewer.querySelector<HTMLButtonElement>('[aria-label="Zoom in"]')?.click(); await settle();
    assert(picture.getBoundingClientRect().width > picture.viewBox.baseVal.width, 'Zoom enlarges the vector drawing');
    button('Fit to window').click(); await settle();
    assert(viewer.querySelector('.goal-diagram__canvas.is-fit') !== null, 'Fit restores a whole-diagram view');
    const fittedBounds = picture.getBoundingClientRect();
    const fittedScale = Math.min(fittedBounds.width / picture.viewBox.baseVal.width, fittedBounds.height / picture.viewBox.baseVal.height);
    viewer.querySelector<HTMLButtonElement>('[aria-label="Zoom in"]')?.click(); await settle();
    assert(Math.abs(picture.getBoundingClientRect().width / picture.viewBox.baseVal.width - Math.min(4, Math.max(0.25, fittedScale * 1.25))) < 0.01,
      'Zoom from Fit uses the visible drawing scale');
    viewer.querySelector<HTMLButtonElement>('[aria-label="Close diagram"]')?.click(); await settle();
    assert(document.querySelector('.goal-diagram__viewer') === null && document.activeElement === expand, 'Closing returns focus to the diagram');

    button('Write again').click(); await settle();
    const again = (): number => server.again;
    assert(again() === 1, 'Write again asks for the chapter to be written again');
    await until(() => text().includes('A table is a tree of metadata files.'), 'The chapter written again replaces the writing state');

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
    assert(opened() === 3 && again() === 1, 'The next chapter is opened, and written, as it is first read');
    button('All chapters').click(); await settle();
    assert(document.querySelector('.goal-overview__chapter.is-done')?.textContent?.includes('What a table is') === true
      && button('Continue: Follow a snapshot') !== undefined, 'All chapters shows the course, the finished chapter checked and where to continue');
    assert(document.querySelector('.goal-overview .goal-diagram.is-components figcaption')?.textContent === 'The concepts of an Iceberg table'
      && document.querySelectorAll('.goal-overview .goal-diagram__edge').length === 2, 'The course opens with its map');
    button('Guide').click(); await settle();
    assert(document.querySelector('.goal-course__lessons li.is-done') !== null, 'The guide shows the lesson done in the course');
    button('Lessons').click(); await settle();
    assert(document.querySelector('.goal-overview') !== null, 'The Lessons tab returns to the course');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
