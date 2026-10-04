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
import type { GoalDto, GoalExchangeDto, GoalItemDto, GoalKnowledgeNoteDto, GoalRecommendationDto, WorkItemDto } from './contracts';
import { parseGoal, parseGoalExchange, parseGoalKnowledgeNote, parseGoalLesson, parseGoalRecommendation } from './contracts';
import { GoalPanel, type GoalPanelProps } from './GoalPanel';
import {
  addable, attentionWorkspace, goalDirections, goalHas, goalRef, itemsIn, needsYouKeys, openCourse, openProposal, placePill, recentActivity, refKey, sourceUrl, summarize,
} from './model';
import { toggleGoalItem, useGoals, type GoalBridge, type GoalConversation, type Goals } from './useGoals';

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
  id: 'goal-1', workspaceId: 'workspace-1', title: 'Iceberg & Delta Lake correctness for 482', description: 'Land the fixes before 482.', pinned: true,
  source: null, definition: null, revision: 0, course: null, recommendations: [], topics: [], items: ITEMS.map((link, index): WorkItemDto => ({
    id: `work-${index}`, workspaceId: 'workspace-1', goalId: 'goal-1', title: link.title, kind: 'DEVELOP', acceptanceCriteria: 'Verified fix',
    completionRule: 'MERGED_DELIVERY', state: index === 1 ? 'DONE' : index === 2 ? 'PLANNED' : 'WORKING', revision: 0, links: [link],
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

  it('counts progress, pull requests, what needs you and what is blocked', () => {
    const needsYou = needsYouKeys([attention({}), attention({ type: 'issue', number: 31302 }), attention({ number: 1 })]);
    expect(summarize(GOAL, needsYou)).toEqual({ total: 5, finished: 1, working: 3, idle: 1, prsMerged: 1, prsOpen: 1, needs: 2, blocked: 1, dropped: 0 });
    expect(itemsIn(GOAL, 'needs', needsYou).map((entry) => entry.links[0].label)).toEqual(['#30925', '#31302']);
    expect(itemsIn(GOAL, 'blocked', needsYou).map((entry) => entry.links[0].label)).toEqual(['TRINO-412']);
    expect(recentActivity(GOAL).map((entry) => entry.text)).toEqual(['#30702 merged', '#30925 updated', 'TRINO-412 is Blocked']);
    expect(goalHas(GOAL, { kind: 'TASK', key: 'task-1' })).toBe(true);
    expect(goalHas(null, { kind: 'TASK', key: 'task-1' })).toBe(false);
    expect(addable([ITEMS[0], item('ISSUE', 'trinodb/trino#1', '#1', 'IDLE', 'Open')], GOAL).map(refKey)).toEqual(['ISSUE trinodb/trino#1']);
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
  });

  it('reads a goal the backend sends and refuses a malformed one', () => {
    expect(parseGoal(JSON.parse(JSON.stringify(GOAL)))).toEqual(GOAL);
    expect(() => parseGoal({ ...GOAL, items: [{ ...GOAL.items[0], state: 'FINISHED' }] })).toThrow('Malformed work item state');
    expect(() => parseGoal({ ...GOAL, pinned: 'yes' })).toThrow('Malformed goal pinned');
  });
});

const NO_CONVERSATION: GoalConversation = { loaded: true, exchanges: [], notes: [], error: null, ask: async () => {}, retry: async () => {}, accept: async () => {},
  acceptCourse: async () => {}, openLesson: async () => { throw new Error('No lesson'); }, readLesson: async () => { throw new Error('No lesson'); }, finishLesson: async () => {},
  correctTopic: async () => {} };

function panel(props: Partial<GoalPanelProps>): string {
  return renderToStaticMarkup(
    <GoalPanel mode="window" view="work" onView={() => {}} conversation={NO_CONVERSATION} onStart={async () => {}} onOpenTask={() => {}} lesson={null} onLesson={() => {}}
      repository="trinodb/trino" goals={[GOAL, { ...GOAL, id: 'goal-2', pinned: false }]} goal={GOAL}
      needsYou={new Set(['PULL_REQUEST trinodb/trino#30925'])} now={Date.parse('2026-09-27T12:00:00Z')}
      onClose={() => {}} onExpand={() => {}} onSwitch={() => {}} onStartIssue={() => {}} onStartTicket={() => {}} onNew={() => {}} onCreate={async () => {}} onRename={async () => {}} onDelete={async () => {}}
      workItems={GOAL.items} onCreateWork={async () => GOAL.items[0]} onDecide={async () => GOAL.items[0]} onLink={async () => GOAL.items[0]} onAdd={async () => {}} onOpenItem={() => {}} search={async () => []} {...props} />);
}

describe('goal panel', () => {
  it('shows one progress card per outcome and keeps ended tasks unfinished', () => {
    const markup = panel({});
    expect(markup).toContain('of 5 done');
    expect(markup).toContain('>Done</span><span class="goal-group__count">1</span>');
    expect(markup).toContain('>Working</span><span class="goal-group__count">3</span>');
    expect(markup).toContain('Task title');
    expect(markup).toContain('New work item');
    expect(markup).toContain('Recent decisions');
  });

  it('shows planned, working, done and dropped on the board', () => {
    const markup = panel({ mode: 'board' });
    expect(markup).toContain('class="goal-columns"');
    for (const label of ['Planned', 'Working', 'Done', 'Dropped']) expect(markup).toContain(`>${label}</span>`);
    expect(markup).toContain('#30702 title');
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
    question: { text: 'Do you already know the Iceberg specification?', suggestions: ['Yes', 'Not yet'] }, proposal: null, recommendation: null, course: null,
  } };
const PROPOSED: GoalExchangeDto = { ...ASKED, id: 'op-2', message: 'Not yet', reply: {
  assessment: 'Start from the specification, then the connector.', question: null,
  proposal: { title: 'Iceberg connector expert', objective: 'Explain and change the Iceberg connector', successDefinition: 'Trace a scan end to end',
    startingKnowledge: 'Knows Trino, not the specification', constraints: '' }, recommendation: null, course: null,
} };
const COURSE: GoalExchangeDto = { ...ASKED, id: 'op-3', message: 'The research run saved 2 notes.', automatic: true, reply: {
  assessment: 'Here is a course from the notes.', question: null, proposal: null, recommendation: null,
  course: { parts: [{ title: 'Iceberg tables', lessons: [{ title: 'What a table is', outcome: 'Explain metadata, snapshots and manifests' }] }] },
} };
const CARD: GoalRecommendationDto = { id: 'card-1', goalId: 'goal-1', state: 'SHOWN', taskId: null, workItemId: null, createdAt: '2026-10-03T10:00:00Z',
  card: { kind: 'RESEARCH', title: 'Read the Iceberg specification', reason: 'You start from the specification', scope: 'Snapshots and manifests',
    pages: ['https://iceberg.apache.org/spec/'], workItemId: null, issue: null, ticket: null } };
const NOTE: GoalKnowledgeNoteDto = { id: 'note-1', topic: 'Manifest lists', body: 'A snapshot lists its manifests in one manifest list.', savedAt: '2026-10-03T10:00:00Z',
  sources: [{ reference: 'https://iceberg.apache.org/spec/#manifest-lists', commit: null },
    { reference: 'apache/iceberg:core/src/main/java/org/apache/iceberg/ManifestLists.java', commit: 'c'.repeat(40) }] };

function conversation(exchanges: GoalExchangeDto[]): GoalConversation {
  return { ...NO_CONVERSATION, exchanges };
}

describe('goal guidance', () => {
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
    expect(parseGoalKnowledgeNote(JSON.parse(JSON.stringify({ ...NOTE, goalId: 'goal-1', taskId: 'task-1' })))).toEqual(NOTE);
    expect(parseGoal(JSON.parse(JSON.stringify({ ...GOAL, recommendations: [CARD] }))).recommendations).toEqual([CARD]);
    expect(parseGoalRecommendation({ ...CARD, state: 'STARTED', workItemId: 'work-9' }).workItemId).toBe('work-9');
    expect(sourceUrl(NOTE.sources[0])).toBe('https://iceberg.apache.org/spec/#manifest-lists');
    expect(sourceUrl(NOTE.sources[1])).toBe(`https://github.com/apache/iceberg/blob/${'c'.repeat(40)}/core/src/main/java/org/apache/iceberg/ManifestLists.java`);
  });

  it('shows a running research run, what it learned and the events the agent was told', () => {
    const markup = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...CARD, state: 'STARTED', taskId: 'task-1' }] },
      conversation: { ...NO_CONVERSATION, notes: [NOTE], exchanges: [ASKED, { ...ASKED, id: 'op-9', automatic: true, message: 'The research run saved 1 note: Manifest lists.' }] } });
    expect(markup).toContain('Researching: Read the Iceberg specification');
    expect(markup).toContain('>Open run</button>');
    expect(markup).toContain('What the agent learned · 1');
    expect(markup).toContain('<summary>Manifest lists</summary>');
    expect(markup).toContain(`ManifestLists.java @ ccccccc`);
    expect(markup).toContain('<div class="goal-thread__event">The research run saved 1 note: Manifest lists.</div>');
  });

  it('shows started practice as practice, leading to its task once sent and to the prepared composer until then', () => {
    const practice: GoalRecommendationDto = { ...CARD, id: 'card-2', state: 'STARTED', workItemId: 'work-9',
      card: { ...CARD.card, kind: 'WORK', title: 'Fix #31252', pages: [], issue: 31252 } };
    const waiting = panel({ view: 'guide', goal: { ...GOAL, recommendations: [practice] } });
    expect(waiting).toContain('Practicing: Fix #31252');
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
    expect(merged).toContain('#31260 merged');
    expect(merged).toContain('>Review result</button>');
    const ticket = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...practice, card: { ...practice.card, title: 'Fix TRINO-12', issue: null,
      ticket: 'connection:cloud:10412' } }] } });
    expect(ticket).toContain('Practicing: Fix TRINO-12');
    expect(ticket).toContain('>Open the composer</button>');
    expect(parseGoalRecommendation({ ...practice, card: { ...practice.card, issue: null, ticket: 'connection:cloud:10412' } }).card.ticket)
      .toBe('connection:cloud:10412');
    const lesson = panel({ view: 'guide', goal: { ...GOAL, recommendations: [{ ...CARD, state: 'STARTED',
      card: { ...CARD.card, kind: 'LESSON', title: 'What a table is', pages: [], workItemId: 'work-0' } }] } });
    expect(lesson).toContain('Learning: What a table is');
    expect(lesson).toContain('>Open lesson</button>');
  });

  it('reads a proposed and an accepted course, and offers the course only until one is accepted', () => {
    expect(parseGoalExchange(JSON.parse(JSON.stringify(COURSE))).reply.course).toEqual(COURSE.reply.course);
    const course = { exchangeId: 'op-3', parts: [{ title: 'Iceberg tables', lessons: ['work-0', 'work-1'] }], acceptedAt: '2026-10-03T10:00:00Z' };
    expect(parseGoal(JSON.parse(JSON.stringify({ ...GOAL, course }))).course).toEqual(course);
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

    const lesson = panel({ view: 'guide', goal: { ...GOAL, course }, lesson: 'work-0' });
    expect(lesson).toContain('Back to guidance');
    expect(lesson).toContain('After this lesson you can: Verified fix');
    expect(lesson).toContain('Opening the lesson…');
    expect(lesson).not.toContain('Proposed course');
    expect(parseGoalLesson({ id: 'work-0', state: 'READY', failure: null, model: 'codex/gpt',
      content: { explanation: '# Tables', exercise: 'Run one test.', observe: 'It passes.', check: 'What is a snapshot?' } }).content?.check).toBe('What is a snapshot?');
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
    expect(markup).toContain('What you know');
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

  it('renames the goal in place, cancels a goal of its own and asks for the next one over the current', () => {
    const shown = panel({ view: 'guide' });
    expect(shown).toContain('<button type="button" class="goal-panel__title" title="Rename">Iceberg &amp; Delta Lake correctness for 482</button>');
    expect(shown).toContain('aria-label="Cancel goal"');
    expect(panel({ view: 'guide', goal: { ...GOAL, source: { kind: 'ISSUE', key: 'trinodb/trino#31302' } } })).not.toContain('aria-label="Cancel goal"');
    const next = panel({ view: 'guide', goal: null, onBack: () => {} });
    expect(next).toContain('What do you want to achieve?');
    expect(next).toContain('>Back to the current goal</button>');
    expect(next).not.toContain('role="tablist"');
    expect(next).not.toContain('goal-switch__pos');
  });

  it('shows the conversation, the question’s answers and a definition to accept', () => {
    const pending = panel({ view: 'guide', conversation: conversation([{ ...ASKED, state: 'PENDING', reply: null }]) });
    expect(pending).toContain('role="status"');
    expect(pending).toContain('Thinking…');
    expect(pending).toMatch(/<button type="submit" class="goal-add" disabled="">Send<\/button>/);

    const asked = panel({ view: 'guide', conversation: conversation([ASKED]) });
    expect(asked).toContain('Do you already know the Iceberg specification?');
    expect(asked).toContain('>Not yet</button>');
    expect(asked).not.toContain('Proposed goal');

    const proposed = panel({ view: 'guide', conversation: conversation([ASKED, PROPOSED]) });
    expect(proposed).toContain('Proposed goal');
    expect(proposed).toContain('value="Iceberg connector expert"');
    expect(proposed).toContain('Trace a scan end to end');
    expect(proposed).toContain('>Accept goal</button>');

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
