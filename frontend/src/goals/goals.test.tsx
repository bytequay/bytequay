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

import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AttentionItem } from '../home/attention';
import type { JiraIssue } from '../jira/contracts';
import type { AgentTaskDto } from '../types';
import type {
  GoalDto, GoalExchangeDto, GoalGapDto, GoalItemDto, GoalKnowledgeNoteDto, GoalPlanChangeDto, GoalRecommendationDto, LessonContentDto, WorkItemDecision, WorkItemDto,
} from './contracts';
import { parseGoal, parseGoalExchange, parseGoalGap, parseGoalKnowledgeNote, parseGoalLesson, parseGoalRecommendation } from './contracts';
import { GoalPanel, type GoalPanelProps } from './GoalPanel';
import { ChapterBody, componentLayout, componentPlaces, flowPlaces, LessonDiagram, treePlaces, wrap } from './LessonBook';
import {
  addable, attentionWorkspace, foldSide, goalDirections, goalHas, goalRef, goalRuns, needsYouKeys, openCourse, openPlan, openProposal, outcomeOf, placePill, goalHistory, refKey, situation,
  sourceOf,
  sourceUrl,
  goalPicked, preparedRequest, summarize, waitingOn, workIn,
} from './model';
import { decideOnCurrent, toggleGoalItem, useGoals, type GoalBridge, type GoalConversation, type Goals } from './useGoals';

afterEach(() => { vi.unstubAllGlobals(); });

function item(kind: GoalItemDto['kind'], key: string, label: string, state: GoalItemDto['state'], status: string, extra: Partial<GoalItemDto> = {}): GoalItemDto {
  return { kind, key, label, title: `${label} title`, state, status, blocked: false, url: null, activity: null, addedAt: '2026-09-20T00:00:00Z', ...extra };
}

const ITEMS: GoalItemDto[] = [
  item('PULL_REQUEST', 'trinodb/trino#30925', '#30925', 'WORKING', 'Open', { activity: { text: '#30925 updated', at: '2026-09-27T08:00:00Z' } }),
  item('PULL_REQUEST', 'trinodb/trino#30702', '#30702', 'MERGED', 'Merged', { activity: { text: '#30702 merged', at: '2026-09-27T09:00:00Z' } }),
  item('ISSUE', 'trinodb/trino#31302', '#31302', 'IDLE', 'Open'),
  item('JIRA', 'connection:cloud:10412', 'TRINO-412', 'WORKING', 'Blocked', { blocked: true, activity: { text: 'TRINO-412 is Blocked', at: '2026-09-26T09:00:00Z' } }),
  item('TASK', 'task-1', 'Task', 'ENDED', 'Completed'),
];
const GOAL: GoalDto = {
  id: 'goal-1', workspaceId: 'workspace-1', title: 'Iceberg & Delta Lake correctness for 482', description: 'Land the fixes before 482.', pinned: true, paused: false,
  source: null, definition: null, revision: 0, course: null, recommendations: [], topics: [], items: ITEMS.map((link, index): WorkItemDto => ({
    id: `work-${index}`, workspaceId: 'workspace-1', goalId: 'goal-1', title: link.title, kind: 'DEVELOP', acceptanceCriteria: 'Verified fix',
    completionRule: 'MERGED_DELIVERY', state: index === 1 ? 'DONE' : index === 2 ? 'PLANNED' : 'WORKING', revision: 0, priority: 'NORMAL', rank: index, blockedBy: [],
    links: [link],
    history: link.activity === null ? [] : [{ operationId: `op-${index}`, action: 'ACCEPT', detail: link.activity.text, at: link.activity.at }],
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-27T00:00:00Z',
  })), createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-27T00:00:00Z',
};

function attention(extra: Partial<AttentionItem>): AttentionItem {
  return {
    key: 'k', type: 'pr', kind: 'review', repo: 'TrinoDB/Trino', number: 30925, title: 't', author: null, authorAvatarUrl: null, createdAt: null,
    updatedAt: '2026-09-27T08:00:00Z', labels: [], pr: null, body: '', commentCount: 0, workspaceId: null, ...extra,
  };
}

describe('goal model', () => {
  it('names an attention card as the goal item it stands for', () => {
    expect(goalRef(attention({}))).toEqual({ kind: 'PULL_REQUEST', key: 'trinodb/trino#30925' });
    expect(goalRef(attention({ type: 'issue', number: 31302 }))).toEqual({ kind: 'ISSUE', key: 'trinodb/trino#31302' });
    const jira = { id: '10412', connectionId: 'connection', cloudId: 'cloud', workspace: { id: 'workspace-2', name: 'Lake' } } as unknown as JiraIssue;
    expect(goalRef(attention({ type: 'issue', jira, repo: '' }))).toEqual({ kind: 'JIRA', key: 'connection:cloud:10412' });
    const task = { id: 'task-1', workspaceId: 'workspace-3' } as AgentTaskDto;
    expect(goalRef(attention({ type: 'task', task: { dto: task, activity: null } }))).toEqual({ kind: 'TASK', key: 'task-1' });
    expect(goalRef(attention({ repo: '', number: 0 }))).toBeNull();

    const workspaces = [{ id: 'workspace-1', monitoredOwner: 'trinodb', monitoredRepo: 'trino' }];
    expect(attentionWorkspace(attention({}), workspaces)).toBe('workspace-1');
    expect(attentionWorkspace(attention({ jira, repo: '' }), workspaces)).toBe('workspace-2');
    expect(attentionWorkspace(attention({ task: { dto: task, activity: null } }), workspaces)).toBe('workspace-3');
    expect(attentionWorkspace(attention({ repo: 'other/repo' }), workspaces)).toBeNull();
  });

  it('shows what finished work achieved and every event of the goal\'s work', () => {
    const at = (minute: number): string => `2026-10-05T10:0${minute}:00Z`;
    const finished: WorkItemDto = { ...GOAL.items[0], id: 'w-1', title: 'Fix #31252', state: 'DONE', links: [], history: [
      { operationId: 'op-1', action: 'ACCEPT', detail: 'User accepted all criteria: Rows survive\nAll rows survive a checkpoint', at: at(1) },
      { operationId: 'op-2', action: 'REOPEN', detail: 'Deletion vectors still drop rows', at: at(2) },
      { operationId: 'op-3', action: 'ACCEPT', detail: 'User accepted all criteria: Rows survive\nRows with deletion vectors survive too\nObserved merged PRs: #31260', at: at(3) },
      { operationId: 'summary:op-3', action: 'SUMMARY', detail: '**What changed** Actions keyed by path', at: at(4) }] };
    const dropped: WorkItemDto = { ...finished, id: 'w-2', title: 'Backport the fix', state: 'DROPPED', history: [
      { operationId: 'op-4', action: 'DROP', detail: 'Not needed for 482', at: at(5) }] };
    expect(outcomeOf(finished)).toBe('Rows with deletion vectors survive too Observed merged PRs: #31260');
    expect(outcomeOf(dropped)).toBe('Not needed for 482');
    expect(outcomeOf(GOAL.items[0])).toBeNull();
    const receipts: WorkItemDto = { ...finished, id: 'w-3', history: [
      { operationId: 'checks:trinodb/trino#31260@abc', action: 'RECEIPT', detail: 'Checks failed on #31260: build', at: at(6) },
      { operationId: 'approved:7', action: 'RECEIPT', detail: '@alice approved #31260', at: at(7) },
      { operationId: 'turn:t-1', action: 'RECEIPT', detail: 'Task turn completed: tests pass', at: at(8) }] };
    expect(goalHistory({ ...GOAL, items: [receipts] }).map((entry) => entry.kind)).toEqual(['Task turn', 'Approved', 'Checks failed']);
    expect(goalHistory({ ...GOAL, items: [finished, dropped] }).map((entry) => `${entry.item.id} ${entry.kind}: ${entry.text}`)).toEqual([
      'w-2 Dropped: Not needed for 482', 'w-1 Summary: ', 'w-1 Accepted: Rows with deletion vectors survive too Observed merged PRs: #31260',
      'w-1 Reopened: Deletion vectors still drop rows', 'w-1 Accepted: All rows survive a checkpoint']);
  });

  it('sends a decision again once when only a receipt moved the work item on', async () => {
    const item: WorkItemDto = { ...GOAL.items[0], revision: 3 };
    const stale = new Error('Work item changed. Refresh before applying this decision.');
    const sent: number[] = [];
    const bridge = {
      decideWorkItem: async (_workspaceId: string, _id: string, command: WorkItemDecision): Promise<WorkItemDto> => {
        sent.push(command.expectedRevision);
        if (command.expectedRevision === 3) throw stale;
        return { ...item, revision: 5, state: 'DONE' };
      },
      listWorkItems: async (): Promise<WorkItemDto[]> => [{ ...item, revision: 4,
        history: [...item.history, { operationId: 'approved:7', action: 'RECEIPT', detail: '@alice approved #31260', at: '2026-10-05T10:00:00Z' }] }],
    };
    const decision: WorkItemDecision = { operationId: 'op-accept', expectedRevision: 3, action: 'ACCEPT', detail: 'Rows survive' };
    expect((await decideOnCurrent(bridge, 'workspace-1', item, decision)).revision).toBe(5);
    expect(sent).toEqual([3, 4]);
    const rescoped = { ...bridge, listWorkItems: async (): Promise<WorkItemDto[]> => [{ ...item, revision: 4, acceptanceCriteria: 'Something else' }] };
    await expect(decideOnCurrent(rescoped, 'workspace-1', item, decision)).rejects.toBe(stale);
  });

  it('knows the sources a card under the Goal control offers as practice', () => {
    const practice = (state: GoalRecommendationDto['state'], issue: number | null, ticket: string | null): GoalRecommendationDto => ({ ...CARD, state,
      card: { ...CARD.card, kind: 'WORK', pages: [], issue, ticket } });
    const goal: GoalDto = { ...GOAL, recommendations: [practice('SHOWN', 31252, null), practice('SHOWN', null, 'connection:cloud:10412'), practice('STARTED', 31300, null)] };
    expect(goalPicked(goal, 'TrinoDB/Trino', { kind: 'ISSUE', key: 'trinodb/trino#31252' })).toBe(true);
    expect(goalPicked(goal, 'trinodb/trino', { kind: 'JIRA', key: 'connection:cloud:10412' })).toBe(true);
    // A started card is work already, and another repository's issue of the same number is another source.
    expect(goalPicked(goal, 'trinodb/trino', { kind: 'ISSUE', key: 'trinodb/trino#31300' })).toBe(false);
    expect(goalPicked(goal, 'apache/iceberg', { kind: 'ISSUE', key: 'trinodb/trino#31252' })).toBe(false);
    expect(goalPicked(null, 'trinodb/trino', { kind: 'ISSUE', key: 'trinodb/trino#31252' })).toBe(false);
  });

  it('prepares the request Goal work opens the task composer with', () => {
    const defined: GoalDto = { ...GOAL, definition: { successDefinition: 'Both fixes merged', startingKnowledge: '', constraints: 'Keep the change in the Delta connector',
      exchangeId: 'op-2', acceptedAt: '2026-10-03T10:00:00Z' } };
    const card = { ...CARD.card, kind: 'WORK' as const, scope: 'Key checkpoint actions by path and deletion vector.', issue: 31252,
      uncertainty: 'Pull request #31270 may already fix it.' };
    const work = { ...GOAL.items[2], acceptanceCriteria: 'Rows with deletion vectors survive a checkpoint' };
    expect(preparedRequest(defined, work, card)).toBe(['Key checkpoint actions by path and deletion vector.',
      'Done when: Rows with deletion vectors survive a checkpoint', 'Check first: Pull request #31270 may already fix it.',
      'My goal: Iceberg & Delta Lake correctness for 482 — Land the fixes before 482.', 'Constraints: Keep the change in the Delta connector'].join('\n\n'));
    // Criteria the scope already states are not repeated; work without a card or a goal starts from its title and criteria.
    expect(preparedRequest(null, { ...work, acceptanceCriteria: card.scope }, card)).toBe(`${card.scope}\n\nCheck first: Pull request #31270 may already fix it.`);
    expect(preparedRequest(null, work, null)).toBe(`${work.title}\n\nDone when: Rows with deletion vectors survive a checkpoint`);
  });

  it('counts progress, pull requests, what needs you and what is blocked', () => {
    const needsYou = needsYouKeys([attention({}), attention({ type: 'issue', number: 31302 }), attention({ number: 1 })]);
    expect(summarize(GOAL, needsYou)).toEqual({ total: 5, finished: 1, working: 3, idle: 1, prsMerged: 1, prsOpen: 1, needs: 2, blocked: 1, dropped: 0 });
    expect(workIn(GOAL.items, 'blocked').map((entry) => entry.links[0].label)).toEqual(['TRINO-412']);
    expect(waitingOn(GOAL.items[3], GOAL.items)).toEqual(['TRINO-412 is Blocked']);
    expect(GOAL.items.map(sourceOf)).toEqual(['none', 'none', 'issue', 'jira', 'none']);
    expect(goalHistory(GOAL).slice(0, 3).map((entry) => `${entry.kind}: ${entry.text}`))
      .toEqual(['Accepted: #30702 merged', 'Accepted: #30925 updated', 'Accepted: TRINO-412 is Blocked']);
    expect(goalHas(GOAL, { kind: 'TASK', key: 'task-1' })).toBe(true);
    expect(goalHas(null, { kind: 'TASK', key: 'task-1' })).toBe(false);
    expect(addable([ITEMS[0], item('ISSUE', 'trinodb/trino#1', '#1', 'IDLE', 'Open')], GOAL).map(refKey)).toEqual(['ISSUE trinodb/trino#1']);
  });

  it('puts ready work first by the user priority, then by the work waiting on it, then by the Goal order', () => {
    const work = (id: string, rank: number, extra: Partial<WorkItemDto> = {}): WorkItemDto => ({ ...GOAL.items[2], id, title: id, rank, links: [], ...extra });
    const items = [work('a', 0), work('b', 1), work('c', 2, { priority: 'HIGH' }), work('d', 3, { blockedBy: ['b'] }), work('e', 4, { priority: 'LOW' }),
      work('f', 5, { state: 'WORKING' }), work('g', 6, { state: 'DONE' })];
    expect(workIn(items, 'next').map((entry) => entry.id)).toEqual(['c', 'b', 'a', 'e']);
    expect(workIn(items, 'blocked').map((entry) => entry.id)).toEqual(['d']);
    expect(waitingOn(items[3], items)).toEqual(['b']);
    expect(workIn(items, 'working').map((entry) => entry.id)).toEqual(['f']);
    expect(workIn(items, 'done').map((entry) => entry.id)).toEqual(['g']);
    // Finishing a blocker makes the work waiting on it ready, in its place.
    const finished = items.map((entry) => entry.id === 'b' ? { ...entry, state: 'DONE' as const } : entry);
    expect(workIn(finished, 'next').map((entry) => entry.id)).toEqual(['c', 'a', 'd', 'e']);
  });

  it('starts the pill just above the composer right edge and never leaves it over the composer', () => {
    const pill = { width: 280, height: 36 };
    const page = { width: 880, height: 952 };
    const composer = { left: 60, top: 746, right: 820, bottom: 900 };
    expect(placePill(null, pill, page, composer)).toEqual({ right: 60, bottom: 216 });
    // A spot saved on the composer is lifted above it; one beside it stays.
    expect(placePill({ right: 100, bottom: 40 }, pill, page, composer)).toEqual({ right: 100, bottom: 216 });
    expect(placePill({ right: 300, bottom: 400 }, pill, page, composer)).toEqual({ right: 300, bottom: 400 });
    // Kept inside the page, and in its corner where there is no composer.
    expect(placePill({ right: 5000, bottom: -20 }, pill, page, null)).toEqual({ right: 592, bottom: 8 });
    expect(placePill(null, pill, page, null)).toEqual({ right: 24, bottom: 24 });
    // A folded tab keeps its side and height, even beside the composer.
    expect(placePill({ right: 100, bottom: 40, fold: 'left' }, pill, page, composer)).toEqual({ right: 100, bottom: 40, fold: 'left' });
  });

  it('folds the pill into the side of the page it is dropped against', () => {
    const page = { left: 240, right: 1240 };
    expect(foldSide(250, page)).toBe('left');
    expect(foldSide(100, page)).toBe('left');
    expect(foldSide(1230, page)).toBe('right');
    expect(foldSide(700, page)).toBeNull();
  });

  it('reads a goal the backend sends and refuses a malformed one', () => {
    expect(parseGoal(JSON.parse(JSON.stringify(GOAL)))).toEqual(GOAL);
    expect(() => parseGoal({ ...GOAL, items: [{ ...GOAL.items[0], state: 'FINISHED' }] })).toThrow('Malformed work item state');
    expect(() => parseGoal({ ...GOAL, pinned: 'yes' })).toThrow('Malformed goal pinned');
  });
});

const NO_CONVERSATION: GoalConversation = { loaded: true, exchanges: [], notes: [], gaps: [], addGap: async () => {}, error: null, model: null, chooseModel: async () => {}, ask: async () => {}, retry: async () => {},
  stop: async () => {}, reassess: async () => {}, accept: async () => {},
  acceptPlan: async () => { throw new Error('No plan change'); },
  acceptCourse: async () => {}, openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {},
  correctTopic: async () => {}, researchRun: async () => ({ buildRequestSequence: 0, canStart: true, build: null }), stopResearch: async () => ({ buildRequestSequence: 0, canStart: true, build: null }) };

function panel(props: Partial<GoalPanelProps>): string {
  return renderToStaticMarkup(
    <GoalPanel mode="window" view="work" onView={() => {}} conversation={NO_CONVERSATION} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      repository="trinodb/trino" goals={[GOAL, { ...GOAL, id: 'goal-2', pinned: false }]} goal={GOAL}
      needsYou={new Set(['PULL_REQUEST trinodb/trino#30925'])} now={Date.parse('2026-09-27T12:00:00Z')}
      onClose={() => {}} onExpand={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onSpecCheck={() => {}} onFileIssue={async () => false} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onPause={async () => {}} onSummarize={async () => {}} onDelete={async () => {}}
      workItems={GOAL.items} onCreateWork={async () => GOAL.items[0]} onDecide={async () => GOAL.items[0]} onOrder={async () => {}} onLink={async () => GOAL.items[0]} onAdd={async () => {}} onOpenItem={() => {}} search={async () => []} {...props} />);
}

describe('goal panel', () => {
  it('shows one progress card per outcome and keeps ended tasks unfinished', () => {
    const markup = panel({});
    expect(markup).toContain('of 5 done');
    // Ended tasks leave their work open: it stays among the working work.
    for (const tab of ['Next 1', 'Working 2', 'Blocked 1', 'Done 1']) expect(markup).toContain(`>${tab}</button>`);
    expect(markup).toContain('#31302 title');
    expect(markup).toContain('New work item');
    expect(markup).toContain('Recent decisions');
  });

  it('shows next, working, blocked and done work side by side on the board', () => {
    const markup = panel({ mode: 'board' });
    expect(markup).toContain('class="goal-columns"');
    for (const label of ['Next', 'Working', 'Blocked', 'Done']) expect(markup).toContain(`>${label}</span>`);
    expect(markup).toContain('#30702 title');
  });

  it('gives an empty goal directions instead of empty counts', () => {
    const markup = panel({ goal: { ...GOAL, items: [] }, goals: [{ ...GOAL, items: [] }] });
    expect(markup).toContain('No work yet');
    expect(markup).not.toContain('of 0 done');
    expect(markup).not.toContain('role="tablist" aria-label="Goal work"');
  });

  it('offers a first goal where the workspace has none', () => {
    const markup = panel({ goals: [], goal: null });
    expect(markup).toContain('No goal yet');
    expect(markup).toContain('aria-label="Goal name"');
  });
});

const ASKED: GoalExchangeDto = { id: 'op-1', goalId: 'goal-1', message: 'I want to become an Iceberg connector expert', automatic: false, lessonId: null, inputRevision: 0,
  state: 'ANSWERED', failure: null, askedAt: '2026-10-03T10:00:00Z', reply: {
    assessment: 'You want to understand the connector deeply.',
    question: { text: 'Do you already know the Iceberg specification?', suggestions: ['Yes', 'Not yet'] }, proposal: null, plan: null, recommendation: null, course: null,
  } };
const PROPOSED: GoalExchangeDto = { ...ASKED, id: 'op-2', message: 'Not yet', reply: {
  assessment: 'Start from the specification, then the connector.', question: null,
  proposal: { title: 'Iceberg connector expert', objective: 'Explain and change the Iceberg connector', successDefinition: 'Trace a scan end to end',
    startingKnowledge: 'Knows Trino, not the specification', constraints: '' }, plan: null, recommendation: null, course: null,
} };
const COURSE: GoalExchangeDto = { ...ASKED, id: 'op-3', message: 'The research run saved 2 notes.', automatic: true, reply: {
  assessment: 'Here is a course from the notes.', question: null, proposal: null, plan: null, recommendation: null,
  course: { parts: [{ title: 'Iceberg tables', lessons: [{ title: 'What a table is', outcome: 'Explain metadata, snapshots and manifests', lessonId: null }] }], reason: null },
} };
const CARD: GoalRecommendationDto = { id: 'card-1', goalId: 'goal-1', state: 'SHOWN', taskId: null, workItemId: null, checkId: null, createdAt: '2026-10-03T10:00:00Z',
  card: { kind: 'RESEARCH', title: 'Read the Iceberg specification', reason: 'You start from the specification', scope: 'Snapshots and manifests',
    pages: ['https://iceberg.apache.org/spec/'], workItemId: null, issue: null, ticket: null, module: null, reproductions: null } };
const NOTE: GoalKnowledgeNoteDto = { id: 'note-1', topic: 'Manifest lists', body: 'A snapshot lists its manifests in one manifest list.', savedAt: '2026-10-03T10:00:00Z', stale: false,
  sources: [{ reference: 'https://iceberg.apache.org/spec/#manifest-lists', commit: null },
    { reference: 'apache/iceberg:core/src/main/java/org/apache/iceberg/ManifestLists.java', commit: 'c'.repeat(40) }] };

const CHAPTER: LessonContentDto = { summary: 'A table is a tree of metadata.', invariants: ['A snapshot never changes once written.'], explanation: null,
  sections: [{ title: 'Table metadata', body: 'The metadata file names the **current snapshot**.', diagram: { kind: 'TREE', caption: 'What a table points to', nodes: [
    { id: 'table', label: 'Table metadata', note: null, parent: null, group: null }, { id: 'snapshot', label: 'Snapshot', note: 'One per commit', parent: 'table', group: null }], edges: [] } },
  { title: 'Writing a snapshot', body: 'A commit swaps the pointer.', diagram: null }],
  exercise: 'Run `TestIcebergMetadata`.', observe: 'The test lists two snapshots.', check: 'What does a snapshot point to?' };

function conversation(exchanges: GoalExchangeDto[]): GoalConversation {
  return { ...NO_CONVERSATION, exchanges };
}

describe('goal guidance', () => {
  it('shows a proposed plan change as the differences it still makes, until the work matches it', () => {
    const work = (id: string, title: string, state: WorkItemDto['state'], rank: number, blockedBy: string[] = []): WorkItemDto =>
      ({ ...GOAL.items[0], id, title, state, rank, blockedBy, priority: 'NORMAL', links: [], history: [] });
    const items = [work('w-1', 'Fix #31252', 'DONE', 0), work('w-2', 'Add a regression test', 'PLANNED', 1, ['w-3']), work('w-3', 'Backport the fix', 'PLANNED', 2),
      work('w-4', 'Write the release note', 'WORKING', 3)];
    const plan: GoalPlanChangeDto = { changes: [{ workItemId: 'w-2', priority: 'HIGH', waitsOn: [], acceptanceCriteria: null },
      { workItemId: 'w-3', priority: null, waitsOn: ['w-2'], acceptanceCriteria: 'The backport keeps the test' }],
      order: ['w-4', 'w-2', 'w-3'], reason: 'The test proves the fix before it is backported' };
    const proposed: GoalExchangeDto = { ...ASKED, id: 'op-9', reply: { ...ASKED.reply, question: null, plan } };
    expect(parseGoalExchange(JSON.parse(JSON.stringify(proposed))).reply?.plan).toEqual(plan);
    const goal: GoalDto = { ...GOAL, items };
    expect(openPlan(goal, [ASKED, proposed])).toEqual({ exchangeId: 'op-9', reason: 'The test proves the fix before it is backported', differences: [
      'Add a regression test: High priority', 'Add a regression test no longer waits on Backport the fix', 'Backport the fix waits on Add a regression test',
      'Backport the fix: criteria become "The backport keeps the test"', 'New order: Write the release note → Add a regression test → Backport the fix'] });
    expect(situation(goal, [ASKED, proposed], [])).toEqual({ text: 'Your decision: the proposed plan change', decisions: 1 });
    const markup = panel({ view: 'guide', goal, conversation: conversation([ASKED, proposed]) });
    expect(markup).toContain('Proposed plan change');
    expect(markup).toContain('Backport the fix waits on Add a regression test');
    expect(markup).toContain('>Accept plan change</button>');
    // Accepted, or made by the user's own hand, the change leaves nothing to accept.
    const applied: GoalDto = { ...goal, items: [items[0], { ...items[3], rank: 1 }, { ...items[1], priority: 'HIGH', blockedBy: [], rank: 2 },
      { ...items[2], blockedBy: ['w-2'], rank: 3, acceptanceCriteria: 'The backport keeps the test' }] };
    expect(openPlan(applied, [ASKED, proposed])).toBeNull();
  });

  it('links references throughout goal conversation, output, notes and definitions in its repository', () => {
    const markup = panel({ view: 'guide', goal: { ...GOAL, description: 'Resolve #41', definition: {
      successDefinition: 'Fix #42', startingKnowledge: 'Read #43', constraints: 'Wait for #44', exchangeId: ASKED.id, acceptedAt: ASKED.askedAt,
    } }, conversation: { ...conversation([{ ...ASKED, message: 'Explain #45', reply: { ...ASKED.reply,
      assessment: 'Compare #46 with other/project#46', question: { text: 'Review #47?', suggestions: [] },
    } }]), notes: [{ ...NOTE, body: 'Investigated #48' }] } });
    for (let number = 41; number <= 48; number++) {
      expect(markup).toContain(`href="https://github.com/trinodb/trino/issues/${number}"><svg`);
      expect(markup).toContain(`</svg>#${number}</a>`);
    }
    expect(markup).toContain('href="https://github.com/other/project/issues/46"><svg');
    expect(markup).toContain('</svg>other/project#46</a>');
  });

  it('reads an exchange and a definition the backend sends and refuses a malformed one', () => {
    expect(parseGoalExchange(JSON.parse(JSON.stringify({ ...PROPOSED, workspaceId: 'workspace-1', model: 'codex/gpt', answeredAt: null })))).toEqual(PROPOSED);
    expect(parseGoalExchange({ ...ASKED, state: 'PENDING', reply: null })).toMatchObject({ state: 'PENDING', reply: null });
    expect(() => parseGoalExchange({ ...ASKED, state: 'THINKING' })).toThrow('Malformed goal exchange state');
    expect(() => parseGoalExchange({ ...ASKED, reply: { ...ASKED.reply, question: { text: 'q', suggestions: 'Yes' } } })).toThrow('Malformed goal suggestions');
    const defined: GoalDto = { ...GOAL, revision: 1, definition: { successDefinition: 'Trace a scan', startingKnowledge: '', constraints: '', exchangeId: 'op-2', acceptedAt: '2026-10-03T10:00:00Z' } };
    expect(parseGoal(JSON.parse(JSON.stringify(defined)))).toEqual(defined);
    expect(() => parseGoal({ ...GOAL, revision: -1 })).toThrow('Malformed goal revision');
  });

  it('reads cards and notes the backend sends and refuses a card of an unknown kind', () => {
    expect(parseGoalRecommendation(JSON.parse(JSON.stringify({ ...CARD, workspaceId: 'workspace-1', exchangeId: 'op-1', updatedAt: CARD.createdAt })))).toEqual(CARD);
    expect(() => parseGoalRecommendation({ ...CARD, card: { ...CARD.card, kind: 'TASK' } })).toThrow('Malformed goal card kind');
    expect(parseGoalRecommendation({ ...CARD, card: { ...CARD.card, kind: 'INVESTIGATE', pages: [] } }).card.kind).toBe('INVESTIGATE');
    expect(parseGoalRecommendation({ ...CARD, card: { ...CARD.card, uncertainty: 'The writer was not read.' } }).card.uncertainty).toBe('The writer was not read.');
    expect(parseGoalRecommendation(CARD).card.uncertainty).toBeUndefined();
    const ready = parseGoalRecommendation({ ...CARD, card: { ...CARD.card, prerequisites: 'Finish the checkpoint lesson', criteria: 'Rows survive a checkpoint' } });
    expect([ready.card.prerequisites, ready.card.criteria]).toEqual(['Finish the checkpoint lesson', 'Rows survive a checkpoint']);
    // A started investigation is the goal's Investigate work, which the Goal window opens.
    const investigating = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...CARD, state: 'STARTED', workItemId: 'work-0',
      card: { ...CARD.card, kind: 'INVESTIGATE', title: 'Check deletion vector support', pages: [] } }] } });
    expect(investigating).toContain('Investigating: Check deletion vector support');
    expect(investigating).toContain('>Open work</button>');
    expect(parseGoalKnowledgeNote(JSON.parse(JSON.stringify({ ...NOTE, goalId: 'goal-1', taskId: 'task-1' })))).toEqual(NOTE);
    expect(parseGoalKnowledgeNote({ ...NOTE, memoryId: 'm7' }).memoryId).toBe('m7');
    expect(parseGoal(JSON.parse(JSON.stringify({ ...GOAL, recommendations: [CARD] }))).recommendations).toEqual([CARD]);
    expect(parseGoalRecommendation({ ...CARD, state: 'STARTED', workItemId: 'work-9' }).workItemId).toBe('work-9');
    expect(sourceUrl(NOTE.sources[0])).toBe('https://iceberg.apache.org/spec/#manifest-lists');
    expect(sourceUrl(NOTE.sources[1])).toBe(`https://github.com/apache/iceberg/blob/${'c'.repeat(40)}/core/src/main/java/org/apache/iceberg/ManifestLists.java`);
  });

  it('shows a running research run, what it learned and the events the agent was told', () => {
    const markup = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...CARD, state: 'STARTED', taskId: 'task-1' }] },
      conversation: { ...NO_CONVERSATION, notes: [NOTE], exchanges: [ASKED, { ...ASKED, id: 'op-9', automatic: true, message: 'The research run saved 1 note: Manifest lists.' }] } });
    expect(markup).toContain('Researching: Read the Iceberg specification');
    // A research run is no task to open; its progress is read from the run while the row shows.
    expect(markup).not.toContain('Open run');
    // The notes fold under their count, out of the conversation's way.
    expect(markup).toContain('<details class="goal-notes goal-fold" aria-label="Knowledge notes"><summary>What the agent learned · 1</summary>');
    expect(markup).toContain('<summary>Manifest lists</summary>');
    const stale = panel({ view: 'guide', conversation: { ...NO_CONVERSATION, notes: [{ ...NOTE, stale: true }], exchanges: [ASKED] } });
    expect(stale).toContain('Manifest lists<span class="goal-note__stale" title="A file it cites changed since the research read it">Stale</span>');
    // A note citing a specification page can be remembered, and then names the memory it became; one citing only code cannot.
    // Each answer says what it read, in counts with the words on hover; an older answer without a record says nothing.
    const covered = panel({ view: 'guide', conversation: { ...NO_CONVERSATION, exchanges: [{ ...ASKED,
      coverage: { notesInFull: 6, notesByTopic: 14, questions: 0, candidates: 100, issuesOpen: 512 } }] } });
    expect(covered).toContain('title="This answer read 6 of 20 notes in full and the rest by topic; 100 practice candidates from 512 open issues."');
    expect(covered).toContain('6/20');
    expect(panel({ view: 'guide', conversation: { ...NO_CONVERSATION, exchanges: [ASKED] } })).not.toContain('goal-thread__coverage');
    expect(parseGoalExchange({ ...ASKED, coverage: { notesInFull: 6, notesByTopic: 14, questions: 3, candidates: 100, issuesOpen: 512 } }).coverage?.questions).toBe(3);
    const remembering = panel({ view: 'guide', conversation: { ...NO_CONVERSATION, rememberNote: async () => {}, exchanges: [ASKED],
      notes: [NOTE, { ...NOTE, id: 'note-2', topic: 'Split planning', sources: [NOTE.sources[1]] }, { ...NOTE, id: 'note-3', topic: 'Snapshots', memoryId: 'm7' }] } });
    expect(remembering.split('>Remember</button>')).toHaveLength(2);
    expect(remembering).toContain('Remembered as m7');
    expect(markup).not.toContain('>Remember</button>');
    expect(markup).toContain(`ManifestLists.java @ ccccccc`);
    expect(markup).toContain('<div class="goal-thread__event" title="The research run saved 1 note: Manifest lists.">The research run saved 1 note: Manifest lists.</div>');
  });

  it('shows started practice as practice, leading to its task once sent and to the prepared composer until then', () => {
    const practice: GoalRecommendationDto = { ...CARD, id: 'card-2', state: 'STARTED', workItemId: 'work-9',
      card: { ...CARD.card, kind: 'WORK', title: 'Fix #31252', pages: [], issue: 31252 } };
    const waiting = panel({ view: 'guide', goal: { ...GOAL, recommendations: [practice] } });
    expect(waiting).toContain('Practicing: Fix <a class="md-reference is-issue" href="https://github.com/trinodb/trino/issues/31252"><svg');
    expect(waiting).not.toContain('Researching');
    expect(waiting).toContain('>Open the composer</button>');
    const sent = panel({ view: 'guide', goal: { ...GOAL, recommendations: [practice],
      items: [...GOAL.items, { ...GOAL.items[0], id: 'work-9', links: [{ ...GOAL.items[0].links[0], kind: 'TASK', key: 'task-31252' }] }] } });
    expect(sent).toContain('>Open task</button>');
    expect(sent).not.toContain('Open the composer');
    expect(sent).not.toContain('Review result');
    const merged = panel({ view: 'guide', goal: { ...GOAL, recommendations: [practice], items: [...GOAL.items, { ...GOAL.items[0], id: 'work-9',
      links: [{ ...GOAL.items[0].links[0], kind: 'TASK', key: 'task-31252' }, { ...GOAL.items[0].links[0], kind: 'PULL_REQUEST', key: 'trinodb/trino#31260', label: '#31260',
        state: 'MERGED', status: 'Merged' }] }] } });
    expect(merged).toContain('#31260</a> merged');
    expect(merged).toContain('>Review result</button>');
    const ticket = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...practice, card: { ...practice.card, title: 'Fix TRINO-12', issue: null,
      ticket: 'connection:cloud:10412' } }] } });
    expect(ticket).toContain('Practicing: Fix TRINO-12');
    expect(ticket).toContain('>Open the composer</button>');
    expect(parseGoalRecommendation({ ...practice, card: { ...practice.card, issue: null, ticket: 'connection:cloud:10412' } }).card.ticket)
      .toBe('connection:cloud:10412');
    const checking = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...CARD, state: 'STARTED', checkId: 'check-1',
      card: { ...CARD.card, kind: 'SPEC_CHECK', title: 'Check the Delta checkpoint writer', module: 'plugin/trino-delta-lake/', reproductions: 3 } }] } });
    expect(checking).toContain('Checking: Check the Delta checkpoint writer');
    expect(checking).toContain('>Open check</button>');
    expect(parseGoalRecommendation({ ...CARD, state: 'STARTED', checkId: 'check-1', card: { ...CARD.card, kind: 'SPEC_CHECK', module: 'plugin/trino-delta-lake/',
      reproductions: 3 } }).card).toMatchObject({ kind: 'SPEC_CHECK', module: 'plugin/trino-delta-lake/', reproductions: 3 });
    const lesson = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...CARD, state: 'STARTED',
      card: { ...CARD.card, kind: 'LESSON', title: 'What a table is', pages: [], workItemId: 'work-0' } }] } });
    expect(lesson).toContain('Learning: What a table is');
    expect(lesson).toContain('>Open lesson</button>');
  });

  it('says what a goal waits on and counts the decisions that are the user’s', () => {
    const defined: GoalDto = { ...GOAL, definition: { successDefinition: 'Trace a scan', startingKnowledge: '', constraints: '', exchangeId: 'op-2',
      acceptedAt: '2026-10-03T10:00:00Z' } };
    expect(situation(GOAL, [], [])).toEqual({ text: 'Clarifying your goal', decisions: 0 });
    expect(situation(GOAL, [{ ...ASKED, state: 'PENDING', reply: null }], []).text).toBe('Waiting for the agent');
    expect(situation(GOAL, [ASKED, PROPOSED], [])).toEqual({ text: 'Your decision: the proposed goal', decisions: 1 });
    expect(situation({ ...defined, recommendations: [{ ...CARD, state: 'STARTED', taskId: 'task-1' }] }, [], []).text).toBe('Researching: Read the Iceberg specification');
    const merged: GoalDto = { ...defined, items: [{ ...defined.items[0], links: [ITEMS[1]] }] };
    expect(situation(merged, [], [])).toEqual({ text: 'Your decision: accept #30925 title', decisions: 1 });
    expect(situation({ ...defined, items: defined.items.map((value) => ({ ...value, state: 'DONE' as const })) }, [], []).text).toBe('Ready to conclude');
    expect(situation(defined, [], []).text).toBe('Working on 4 work items');
    expect(panel({ view: 'guide' })).toContain('<div class="goal-panel__situation">Clarifying your goal</div>');
  });

  it('names what runs for the goal: its agent, a research run or spec check, and the tasks of its work', () => {
    expect(goalRuns(GOAL, [ASKED])).toEqual([]);
    const task = item('TASK', 'task-2', 'Task', 'WORKING', 'Running', { title: 'Fix the checkpoint' });
    const busy: GoalDto = { ...GOAL, recommendations: [{ ...CARD, state: 'STARTED', taskId: 'task-9' }, { ...CARD, id: 'card-2' },
      { ...CARD, id: 'card-3', state: 'STARTED', checkId: 'check-1', card: { ...CARD.card, kind: 'SPEC_CHECK', title: 'Check the Delta checkpoint' } },
      // A lesson stays started while the user reads it.
      { ...CARD, id: 'card-4', state: 'STARTED', card: { ...CARD.card, kind: 'LESSON', title: 'What a table is' } }],
      // A task linked to two work items runs once.
      items: [...GOAL.items, { ...GOAL.items[0], id: 'work-8', links: [task] }, { ...GOAL.items[2], id: 'work-9', links: [task] }] };
    expect(goalRuns(busy, [ASKED, { ...PROPOSED, state: 'PENDING', reply: null }])).toEqual(['Thinking…', 'Researching: Read the Iceberg specification',
      'Checking: Check the Delta checkpoint', 'Running: Fix the checkpoint']);
  });

  it('lists the gaps the last spec check reproduced, to file and to add to the goal', () => {
    const gap: GoalGapDto = { checkId: 'check-1', candidateId: 'c-1', module: 'plugin/trino-delta-lake/', title: 'Checkpoint keys actions by path only',
      rule: 'Reconcile by path and deletion vector', specUrl: 'https://github.com/delta-io/delta/blob/master/PROTOCOL.md',
      test: { path: 'TestCheckpoint.java', command: 'mvn test', exitCode: 1 }, draft: { title: 'Checkpoint loses rows', body: 'Body', labels: ['bug'] },
      workItemId: null, state: null, filed: null };
    expect(parseGoalGap(JSON.parse(JSON.stringify({ ...gap, test: { ...gap.test, output: 'Rows lost', source: 'provider' } })))).toEqual(gap);
    const open = panel({ view: 'guide', conversation: { ...NO_CONVERSATION, gaps: [gap] } });
    expect(open).toContain('Gaps the spec check reproduced · 1');
    expect(open).toContain('>File issue</button>');
    expect(open).toContain('>Add to goal</button>');
    const added = panel({ view: 'guide', conversation: { ...NO_CONVERSATION, gaps: [{ ...gap, workItemId: 'work-7', state: 'PLANNED' }] } });
    expect(added).toContain('>Work: planned</button>');
    expect(added).not.toContain('Add to goal');
    // The issue the user filed from the draft is offered to the gap's work, once added or as it is added.
    expect(panel({ view: 'guide', conversation: { ...NO_CONVERSATION, gaps: [{ ...gap, filed: 31301 }] } })).toContain('>Add to goal with #31301</button>');
    expect(panel({ view: 'guide', conversation: { ...NO_CONVERSATION, gaps: [{ ...gap, workItemId: 'work-7', state: 'PLANNED', filed: 31301 }] } }))
      .toMatch(/>Link #(<!-- -->)?31301(<!-- -->)? to its work<\/button>/);
  });

  it('reads a proposed and an accepted course, and offers the course only until one is accepted', () => {
    expect(parseGoalExchange(JSON.parse(JSON.stringify(COURSE))).reply.course).toEqual(COURSE.reply.course);
    const course: NonNullable<GoalDto['course']> = { exchangeId: 'op-3', parts: [{ title: 'Iceberg tables', lessons: ['work-0', 'work-1'] }], acceptedAt: '2026-10-03T10:00:00Z',
      map: null };
    expect(parseGoal(JSON.parse(JSON.stringify({ ...GOAL, course }))).course).toEqual(course);
    const mapped: NonNullable<GoalDto['course']> = { ...course, map: { kind: 'COMPONENTS', caption: 'The concepts', nodes: [
      { id: 'table', label: 'TableMetadata', note: null, parent: null, group: 'Metadata' }, { id: 'scan', label: 'TableScan', note: null, parent: null, group: 'Engine' }],
    edges: [{ from: 'scan', to: 'table', label: 'reads' }] } };
    expect(parseGoal(JSON.parse(JSON.stringify({ ...GOAL, course: mapped }))).course).toEqual(mapped);
    expect(openCourse(GOAL, [ASKED, COURSE])).toEqual({ exchangeId: 'op-3', course: COURSE.reply.course });
    expect(openCourse({ ...GOAL, course }, [ASKED, COURSE])).toBeNull();

    const proposed = panel({ view: 'guide', conversation: conversation([ASKED, COURSE]) });
    expect(proposed).toContain('Proposed course');
    expect(proposed).toContain('Explain metadata, snapshots and manifests');
    expect(proposed).toContain('>Accept course</button>');
    const accepted = panel({ view: 'guide', goal: { ...GOAL, course }, conversation: conversation([ASKED, COURSE]) });
    expect(accepted).not.toContain('Proposed course');
    expect(accepted).toContain('aria-label="Course"');
    expect(accepted).toContain('1. Iceberg tables');
    expect(accepted).toContain('#30925 title');
    expect(accepted).toContain('<button type="button" class="goal-course__open">#30925 title</button>');

    // A later course revises the accepted one: it keeps a lesson by id, adds one and leaves an unfinished one out.
    const started: NonNullable<GoalDto['course']> = { exchangeId: 'op-3', parts: [{ title: 'Iceberg tables', lessons: ['work-0', 'work-2'] }], acceptedAt: '2026-10-03T10:00:00Z',
      map: null };
    const revision: GoalExchangeDto = { ...COURSE, id: 'op-4', reply: { ...COURSE.reply, course: { reason: 'You know tables already.', parts: [{ title: 'The connector',
      lessons: [{ title: 'Tables', outcome: 'Tables', lessonId: 'work-0' }, { title: 'Plan a scan', outcome: 'Explain splits', lessonId: null }] }] } } };
    expect(openCourse({ ...GOAL, course: started }, [ASKED, COURSE, revision])).toEqual({ exchangeId: 'op-4', course: revision.reply.course });
    const revised = panel({ view: 'guide', goal: { ...GOAL, course: started }, conversation: conversation([ASKED, COURSE, revision]) });
    expect(revised).toContain('Revised course');
    expect(revised).toContain('You know tables already.');
    expect(revised).toContain('#30925</a> title</span>');
    expect(revised).toContain('Plan a scan<span class="goal-course__new">New</span>');
    expect(revised).toContain('Leaves the course');
    expect(revised).toContain('>Accept revision</button>');

    // A goal with a course reads its lessons under their own tab.
    expect(panel({ view: 'guide', goal: { ...GOAL, course } })).toMatch(/>Guide<\/button><button [^>]*>Lessons<\/button><button [^>]*>Work<\/button>/);
    expect(panel({ view: 'guide' })).not.toContain('>Lessons</button>');
    const overview = panel({ view: 'lessons', goal: { ...GOAL, course } });
    expect(overview).toContain('<h1 class="goal-chapter__title">Iceberg &amp; Delta Lake correctness for 482</h1>');
    expect(overview).toContain('>Part 1 · Iceberg tables</h2>');
    expect(overview).toMatch(/>(Start|Continue): <!-- -->#30925 title<\/button>|>(Start|Continue): #30925 title<\/button>/);
    const lesson = panel({ view: 'lessons', goal: { ...GOAL, course }, lesson: 'work-0' });
    expect(lesson).toContain('>All chapters</button>');
    expect(lesson).toContain('After this chapter you can: Verified fix');
    expect(lesson).toContain('Opening the lesson…');
    expect(lesson).not.toContain('Proposed course');
    // The floating window shows the chapter alone, with its own question field.
    expect(lesson).not.toContain('goal-book');
    expect(lesson).toContain('placeholder="Ask about this lesson"');
    // The expanded window reads as a book: its contents mark the open chapter, and the questions wait until asked for.
    const book = panel({ mode: 'board', view: 'lessons', goal: { ...GOAL, course }, lesson: 'work-0', conversation: conversation([ASKED, COURSE]) });
    expect(book).toMatch(/^.*<div class="goal-book"><nav class="goal-book__contents" aria-label="Contents">.*Part 1 · Iceberg tables.*aria-current="page" class="goal-book__chapter is-current/);
    expect(book).toContain('<button type="button" class="goal-chapter__ask" aria-pressed="false">');
    expect(book).not.toContain('goal-book__ask');
    expect(book).not.toContain('<textarea');
    expect(parseGoalLesson({ id: 'work-0', state: 'READY', failure: null, model: 'codex/gpt', content: CHAPTER }).content?.sections[0].diagram?.nodes[1])
      .toEqual({ id: 'snapshot', label: 'Snapshot', note: 'One per commit', parent: 'table', group: null });
    // A lesson written before chapters still reads, as its explanation, until opening writes it again.
    expect(parseGoalLesson({ id: 'work-0', state: 'READY', failure: null, model: 'codex/gpt', content: { ...CHAPTER, summary: null, invariants: [], sections: [],
      explanation: '# Tables' } }).content?.explanation).toBe('# Tables');
    expect(() => parseGoalLesson({ id: 'work-0', state: 'DRAFT' })).toThrow('Malformed goal lesson state');
    expect(parseGoalRecommendation({ ...CARD, card: { ...CARD.card, kind: 'LESSON', workItemId: 'work-0' } }).card.workItemId).toBe('work-0');
    expect(parseGoalRecommendation({ ...CARD, card: { ...CARD.card, kind: 'WORK', issue: 31252 } }).card.issue).toBe(31252);
    expect(parseGoalRecommendation({ ...CARD, card: { ...CARD.card, kind: 'WORK', issue: '31252' } }).card.issue).toBeNull();
  });

  it('shows what the user knows with a level they can correct', () => {
    const known = { ...GOAL, topics: [{ topic: 'Manifests', level: 'LEARNING' as const, evidence: ['discussion:q-1'], userSet: false },
      { topic: 'Snapshots', level: 'STRONG' as const, evidence: [] as string[], userSet: true }] };
    expect(parseGoal(JSON.parse(JSON.stringify(known))).topics).toEqual(known.topics);
    expect(() => parseGoal({ ...known, topics: [{ ...known.topics[0], level: 'EXPERT' }] })).toThrow('Malformed goal topic level');
    const markup = panel({ view: 'guide', goal: known });
    expect(markup).toContain('<details class="goal-known goal-fold" aria-label="What you know"><summary>What you know · 2</summary>');
    expect(markup).toContain('aria-label="Your level in Manifests"');
    expect(markup).toMatch(/<option value="LEARNING" selected="">Learning<\/option>/);
    expect(markup).toContain('set by you');
  });

  it('offers directions from the repository and only the newest unaccepted proposal', () => {
    expect(goalDirections('trinodb/trino')).toEqual(['Understand how trino works, one area at a time', 'Fix real bugs in trino and get them merged',
      'Review trino pull requests with confidence']);
    expect(openProposal(GOAL, [ASKED, PROPOSED])).toEqual({ exchangeId: 'op-2', proposal: PROPOSED.reply.proposal });
    expect(openProposal(GOAL, [PROPOSED, ASKED])).toBeNull();
    expect(openProposal({ ...GOAL, definition: { successDefinition: 's', startingKnowledge: '', constraints: '', exchangeId: 'op-2', acceptedAt: '' } }, [ASKED, PROPOSED])).toBeNull();
  });

  it('opens on what the user wants to achieve, with directions and no model call', () => {
    const empty = panel({ view: 'guide', goals: [], goal: null });
    expect(empty).toContain('What do you want to achieve?');
    expect(empty).toContain('Review trino pull requests with confidence');
    expect(empty).toContain('aria-label="Message the goal&#x27;s agent"');
    expect(empty).not.toContain('No goal yet');
    // A goal named before its conversation gets the same question, never its own title back.
    const named = panel({ view: 'guide', goal: { ...GOAL, items: [] } });
    expect(named).toContain('What do you want to achieve?');
    expect(named).not.toContain('mean to you');
    // The answer is typed right under the question, ahead of the directions.
    const ask = named.indexOf('What do you want to achieve?');
    const field = named.indexOf('placeholder="I want to…"');
    expect(ask).toBeGreaterThan(-1);
    expect(field).toBeGreaterThan(ask);
    expect(named.indexOf('Review trino pull requests with confidence')).toBeGreaterThan(field);
    expect(named).toContain('data-goal-focus="true"');
    // A conversation not yet read may have begun, so it shows neither the question nor a field.
    const unread = panel({ view: 'guide', goal: { ...GOAL, items: [] }, conversation: { ...NO_CONVERSATION, loaded: false } });
    expect(unread).not.toContain('What do you want to achieve?');
    expect(unread).not.toContain('<textarea');
  });

  it('renames the goal in place, cancels any goal independently of its source and asks for the next one over the current', () => {
    const shown = panel({ view: 'guide' });
    expect(shown).toContain('<button type="button" class="goal-panel__title" title="Rename">Iceberg &amp; Delta Lake correctness for 482</button>');
    expect(shown).toContain('aria-label="Cancel goal"');
    expect(panel({ view: 'guide', goal: { ...GOAL, source: { kind: 'ISSUE', key: 'trinodb/trino#31302' } } })).toContain('aria-label="Cancel goal"');
    const next = panel({ view: 'guide', goal: null, onBack: () => {} });
    expect(next).toContain('What do you want to achieve?');
    expect(next).toContain('>Back to the current goal</button>');
    expect(next).not.toContain('role="tablist"');
    expect(next).not.toContain('goal-switch__pos');
  });

  it('shows the conversation, the question’s answers and a definition to accept', () => {
    const pending = panel({ view: 'guide', now: Date.parse('2026-10-03T10:01:20Z'), conversation: conversation([{ ...ASKED, state: 'PENDING', reply: null }]) });
    expect(pending).toContain('<span role="status">Thinking…</span><span class="goal-muted">1:20</span>');
    expect(pending).toContain('>Stop</button>');
    expect(pending).toMatch(/<button type="submit" class="goal-add" disabled="">Send<\/button>/);

    const asked = panel({ view: 'guide', conversation: conversation([ASKED]) });
    expect(asked).toContain('Do you already know the Iceberg specification?');
    expect(asked).toContain('aria-pressed="false">Not yet</button>');
    expect(asked).toContain('>Other options</button>');
    expect(asked).not.toContain('Proposed goal');
    // One scroll holds the goal's description, the conversation and, last, the question with its answers; only the reply field stays under it.
    const scroll = asked.indexOf('<div class="goal-guide__scroll">');
    const dock = asked.indexOf('<div class="goal-guide__dock">');
    expect(asked.split('Do you already know the Iceberg specification?')).toHaveLength(2);
    expect(scroll).toBeLessThan(asked.indexOf('Land the fixes before 482.'));
    expect(asked.indexOf('<div class="goal-guide__question">Do you already know the Iceberg specification?</div><div class="goal-choices">'))
      .toBeGreaterThan(asked.indexOf('<ol class="goal-thread"'));
    expect(asked.indexOf('>Other options</button>')).toBeLessThan(dock);
    expect(asked.slice(dock)).toMatch(/^<div class="goal-guide__dock"><form class="goal-composer">/);

    const proposed = panel({ view: 'guide', conversation: conversation([ASKED, PROPOSED]) });
    expect(proposed).toContain('Proposed goal');
    expect(proposed).toContain('value="Iceberg connector expert"');
    expect(proposed).toContain('Trace a scan end to end');
    // The proposal reads in the conversation; deciding on it happens above the reply field.
    expect(proposed.indexOf('Trace a scan end to end')).toBeLessThan(proposed.indexOf('<div class="goal-guide__dock">'));
    expect(proposed).toContain('<div class="goal-guide__decision"><button type="button" class="goal-add is-primary">Accept goal</button>'
      + '<button type="button" class="goal-link">Adjust</button><button type="button" class="goal-link">Skip</button></div>');

    const failed = panel({ view: 'guide', conversation: conversation([{ ...ASKED, state: 'FAILED', reply: null, failure: 'Codex is not signed in' }]) });
    expect(failed).toContain('Codex is not signed in');
    expect(failed).toContain('>Ask again</button>');

    const defined = panel({ view: 'guide', goal: { ...GOAL, definition: { successDefinition: 'Trace a scan end to end', startingKnowledge: 'Knows Trino', constraints: '',
      exchangeId: 'op-2', acceptedAt: '2026-10-03T10:00:00Z' } }, conversation: conversation([ASKED, PROPOSED]) });
    expect(defined).toContain('Success means');
    expect(defined).toContain('You already know');
    expect(defined).not.toContain('Proposed goal');
  });
});

describe('goal changes', () => {
  /** Every bridge method a call touches, answering each goal method with the goal. */
  function spyBridge(): { bridge: GoalBridge; touched: string[] } {
    const touched: string[] = [];
    const bridge = new Proxy({}, {
      get: (_target, name) => {
        touched.push(String(name));
        return async () => (name === 'listGoals' ? [GOAL] : name === 'searchGoalCandidates' ? [] : name === 'deleteGoal' ? undefined : GOAL);
      },
    }) as GoalBridge;
    return { bridge, touched };
  }

  it('adds and removes items with goal calls alone, never a GitHub or Jira write', async () => {
    const events: string[] = [];
    vi.stubGlobal('window', { dispatchEvent: (event: CustomEvent<string>) => { events.push(event.detail); return true; } });
    const { bridge, touched } = spyBridge();
    let goals: Goals | undefined;
    function Capture(): null {
      goals = useGoals('workspace-1', () => bridge);
      return null;
    }
    renderToStaticMarkup(<Capture />);

    await goals?.add('goal-1', 'PULL_REQUEST', 'trinodb/trino#30925');
    await goals?.removeItem('goal-1', 'JIRA', 'connection:cloud:10412');
    await toggleGoalItem('workspace-1', 'goal-1', { kind: 'ISSUE', key: 'trinodb/trino#31302' }, false, bridge);
    await toggleGoalItem('workspace-1', 'goal-1', { kind: 'TASK', key: 'task-1' }, true, bridge);

    expect(touched).toEqual(['addGoalItem', 'removeGoalItem', 'addGoalItem', 'removeGoalItem']);
    // Every other view of the workspace's goals reads them again.
    expect(events).toEqual(['workspace-1', 'workspace-1', 'workspace-1', 'workspace-1']);
  });
});

describe('lesson book', () => {
  it('draws a chapter: its summary, what to remember, its sections with their diagrams and its exercise', () => {
    const markup = renderToStaticMarkup(<ChapterBody content={CHAPTER} repository="trinodb/trino" />);
    expect(markup).toContain('<p class="goal-chapter__summary">A table is a tree of metadata.</p>');
    expect(markup).toContain('<aside class="goal-chapter__remember" aria-label="Remember"><div class="goal-chapter__kicker">Remember</div>'
      + '<ul><li>A snapshot never changes once written.</li></ul></aside>');
    expect(markup).toMatch(/<section class="goal-chapter__section" data-section="0"><h2>Table metadata<\/h2><figure class="goal-diagram is-tree"><svg [^>]*role="img" aria-label="What a table points to"/);
    expect(markup.match(/<rect /g)).toHaveLength(2);
    expect(markup).toContain('<figcaption>What a table points to</figcaption>');
    expect(markup).toMatch(/<section class="goal-chapter__section" data-section="1"><h2>Writing a snapshot<\/h2><div/);
    expect(markup).toContain('>Try it</div>');
    expect(markup).toContain('The test lists two snapshots.');
  });

  it('lays a flow out four to a row and a tree with each parent over its children', () => {
    expect(flowPlaces(5, 52).map(({ x, y }) => `${x},${y}`)).toEqual(['0,0', '220,0', '440,0', '660,0', '0,96']);
    const node = (id: string, parent: string | null) => ({ id, label: id, note: null as string | null, parent, group: null as string | null });
    expect(treePlaces([node('root', null), node('a', 'root'), node('b', 'root'), node('a1', 'a')], 52))
      .toEqual([{ x: 110, y: 0 }, { x: 0, y: 96 }, { x: 220, y: 96 }, { x: 0, y: 192 }]);
    expect(wrap('Swap the metadata pointer atomically in the catalog', 22)).toEqual(['Swap the metadata', 'pointer atomically in…']);
  });

  it('keeps dense component labels and connections outside boxes and inside the drawing', () => {
    const nodes = Array.from({ length: 12 }, (_, at) => ({ id: `n${at}`, label: `Component ${at}`, note: 'Some implementation detail', parent: null as string | null, group: `Group ${at % 7}` }));
    const edges = Array.from({ length: 24 }, (_, at) => ({ from: `n${at % 12}`, to: `n${(at * 3 + 7) % 12}`,
      label: `Request ${at}: metadata, table requirements and updates returned by the catalog implementation` }));
    const layout = componentLayout({ kind: 'COMPONENTS', caption: 'Dense map', nodes, edges });
    let previousBottom = layout.bottom;
    for (const arrow of layout.arrows) {
      expect(arrow.y - 9).toBeGreaterThan(previousBottom);
      previousBottom = arrow.y + arrow.lines.length * 16;
      expect(previousBottom).toBeLessThan(layout.height);
      expect(arrow.lines.join(' ')).not.toContain('…');
      const half = Math.max(...arrow.lines.map((line) => line.length)) * 6.2 / 2 + 6;
      expect(arrow.x - half).toBeGreaterThanOrEqual(0);
      expect(arrow.x + half).toBeLessThan(layout.width);
      for (let at = 1; at < arrow.points.length; at++) {
        const start = arrow.points[at - 1];
        const end = arrow.points[at];
        for (const box of layout.places) {
          const crosses = start.x === end.x
            ? start.x > box.x && start.x < box.x + 172 && Math.max(start.y, end.y) > box.y && Math.min(start.y, end.y) < box.y + layout.boxHeight
            : start.y > box.y && start.y < box.y + layout.boxHeight && Math.max(start.x, end.x) > box.x && Math.min(start.x, end.x) < box.x + 172;
          expect(crosses).toBe(false);
        }
      }
    }
  });

  it('draws components in the columns of their groups and a sequence as numbered messages', () => {
    const component = (id: string, group: string) => ({ id, label: id, note: null as string | null, parent: null as string | null, group });
    expect(componentPlaces([component('manager', 'Coordinator'), component('task', 'Worker'), component('group', 'Coordinator')], 52, 30).places)
      .toEqual([{ x: 0, y: 30 }, { x: 276, y: 30 }, { x: 0, y: 126 }]);
    const parts = renderToStaticMarkup(<LessonDiagram diagram={{ kind: 'COMPONENTS', caption: 'Admission', nodes: [component('manager', 'Coordinator'),
      component('task', 'Worker')], edges: [{ from: 'manager', to: 'task', label: 'schedules' }] }} />);
    expect(parts).toContain('<g class="goal-diagram__zone"><rect');
    expect(parts).toContain('>Coordinator</text>');
    expect(parts).toContain('>schedules</tspan>');
    const calls = renderToStaticMarkup(<LessonDiagram diagram={{ kind: 'SEQUENCE', caption: 'Admitting a query', nodes: [component('query', ''), component('group', '')],
      edges: [{ from: 'query', to: 'group', label: 'submit' }, { from: 'group', to: 'query', label: 'start' }] }} />);
    expect(calls.match(/class="goal-diagram__lifeline"/g)).toHaveLength(2);
    expect(calls).toContain('>1. submit</text>');
    expect(calls).toContain('>2. start</text>');
  });
});
