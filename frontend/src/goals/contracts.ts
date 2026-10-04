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

import { object, oneOf, optionalText, text } from '../pulls/wireParse';

export const GOAL_ITEM_KINDS = ['PULL_REQUEST', 'ISSUE', 'JIRA', 'TASK'] as const;
export type GoalItemKind = typeof GOAL_ITEM_KINDS[number];
export type GoalItemState = 'IDLE' | 'WORKING' | 'MERGED' | 'ENDED' | 'UNKNOWN';

/** A goal item, or a candidate for one, as the backend read it from local stores. */
export type GoalItemDto = {
  kind: GoalItemKind;
  /** owner/repo#number, connection:cloud:issue for a Jira ticket, or the task id. */
  key: string;
  /** #123, TRINO-412 or Task. */
  label: string;
  title: string;
  state: GoalItemState;
  /** The item's own state in words, such as Merged, In Progress or Running. */
  status: string;
  blocked: boolean;
  url: string | null;
  activity: { text: string; at: string } | null;
  /** When it joined the goal; null for a candidate. */
  addedAt: string | null;
};

/** What the user agreed the goal means, accepted from one of the agent's proposals. */
export type GoalDefinitionDto = {
  successDefinition: string;
  startingKnowledge: string;
  constraints: string;
  /** The exchange whose proposal was accepted. */
  exchangeId: string;
  acceptedAt: string;
};

export type GoalDto = {
  id: string;
  workspaceId: string;
  title: string;
  description: string;
  pinned: boolean;
  /** The issue or Jira ticket marked as a Goal that this goal stands for. */
  source: { kind: GoalItemKind; key: string } | null;
  definition: GoalDefinitionDto | null;
  /** Counts changes to the title, objective and definition; a proposal built from an older revision cannot be accepted. */
  revision: number;
  /** The learning path the user accepted: parts of ordered lessons, each a Learn work item of the goal. */
  course: { exchangeId: string; parts: { title: string; lessons: string[] }[]; acceptedAt: string } | null;
  /** While paused, the goal's agent hears no automatic events; the user's own messages still reach it. */
  paused: boolean;
  items: WorkItemDto[];
  /** The cards still shown or running, newest first. */
  recommendations: GoalRecommendationDto[];
  /** What the user knows, topic by topic, and the evidence behind each level. */
  topics: GoalTopicDto[];
  createdAt: string;
  updatedAt: string;
};

export type GoalPatch = { title?: string; description?: string; pinned?: true; paused?: boolean };

export function parseGoalItem(value: unknown): GoalItemDto {
  const source = object(value, 'goal item');
  const activity = source.activity === null || source.activity === undefined ? null : object(source.activity, 'goal item activity');
  if (typeof source.blocked !== 'boolean') throw new Error('Malformed goal item blocked');
  return {
    kind: oneOf(source.kind, GOAL_ITEM_KINDS, 'goal item kind'),
    key: text(source.key, 'goal item key'),
    label: text(source.label, 'goal item label'),
    title: text(source.title, 'goal item title'),
    state: oneOf(source.state, ['IDLE', 'WORKING', 'MERGED', 'ENDED', 'UNKNOWN'] as const, 'goal item state'),
    status: text(source.status, 'goal item status'),
    blocked: source.blocked,
    url: optionalText(source.url),
    activity: activity === null ? null : { text: text(activity.text, 'goal activity text'), at: text(activity.at, 'goal activity time') },
    addedAt: optionalText(source.addedAt),
  };
}

function revision(value: unknown, what: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new Error(`Malformed ${what}`);
  return value as number;
}

export function parseGoal(value: unknown): GoalDto {
  const goal = object(value, 'goal');
  const origin = goal.source === null || goal.source === undefined ? null : object(goal.source, 'goal source');
  const definition = goal.definition === null || goal.definition === undefined ? null : object(goal.definition, 'goal definition');
  const course = goal.course === null || goal.course === undefined ? null : object(goal.course, 'goal course');
  if (typeof goal.pinned !== 'boolean') throw new Error('Malformed goal pinned');
  if (typeof goal.paused !== 'boolean') throw new Error('Malformed goal paused');
  if (!Array.isArray(goal.items)) throw new Error('Malformed goal items');
  return {
    id: text(goal.id, 'goal id'),
    workspaceId: text(goal.workspaceId, 'goal workspace'),
    title: text(goal.title, 'goal title'),
    description: text(goal.description, 'goal description'),
    pinned: goal.pinned,
    source: origin === null ? null : { kind: oneOf(origin.kind, GOAL_ITEM_KINDS, 'goal source kind'), key: text(origin.key, 'goal source key') },
    definition: definition === null ? null : {
      successDefinition: text(definition.successDefinition, 'goal success definition'),
      startingKnowledge: text(definition.startingKnowledge, 'goal starting knowledge'),
      constraints: text(definition.constraints, 'goal constraints'),
      exchangeId: text(definition.exchangeId, 'goal definition exchange'),
      acceptedAt: text(definition.acceptedAt, 'goal definition time'),
    },
    revision: revision(goal.revision, 'goal revision'),
    course: course === null ? null : {
      exchangeId: text(course.exchangeId, 'goal course exchange'),
      parts: parseList(course.parts, (entry) => {
        const part = object(entry, 'goal course part');
        return { title: text(part.title, 'goal course part title'), lessons: parseList(part.lessons, (lesson) => text(lesson, 'goal lesson'), 'goal lessons') };
      }, 'goal course parts'),
      acceptedAt: text(course.acceptedAt, 'goal course time'),
    },
    paused: goal.paused,
    items: goal.items.map(parseWorkItem),
    recommendations: parseList(goal.recommendations, parseGoalRecommendation, 'goal recommendations'),
    topics: parseList(goal.topics, (entry) => {
      const topic = object(entry, 'goal topic');
      if (typeof topic.userSet !== 'boolean') throw new Error('Malformed goal topic owner');
      return {
        topic: text(topic.topic, 'goal topic'),
        level: oneOf(topic.level, TOPIC_LEVELS, 'goal topic level'),
        evidence: parseList(topic.evidence, (value) => text(value, 'goal topic evidence'), 'goal topic evidence'),
        userSet: topic.userSet,
      };
    }, 'goal topics'),
    createdAt: text(goal.createdAt, 'goal created'),
    updatedAt: text(goal.updatedAt, 'goal updated'),
  };
}

export type GoalProposalDto = { title: string; objective: string; successDefinition: string; startingKnowledge: string; constraints: string };

export const TOPIC_LEVELS = ['NEW', 'LEARNING', 'PRACTICED', 'STRONG'] as const;
export type TopicLevel = typeof TOPIC_LEVELS[number];
/** What the user knows about one topic; a level they set is theirs, and the agent leaves it alone. */
export type GoalTopicDto = { topic: string; level: TopicLevel; evidence: string[]; userSet: boolean };

/** A course the agent proposes: parts of ordered lessons, each naming what the user can do afterward. */
export type GoalCourseDraftDto = { parts: { title: string; lessons: { title: string; outcome: string }[] }[] };

/**
 * What a card under the Goal control offers: research reads its scope and pages, a lesson card opens its lesson, and a work card opens the
 * composer of its issue with the scope as the instruction.
 */
export type GoalCardDto = {
  kind: 'RESEARCH' | 'LESSON' | 'WORK' | 'SPEC_CHECK';
  title: string;
  reason: string;
  scope: string;
  pages: string[];
  workItemId: string | null;
  issue: number | null;
  /** The Jira ticket a work card names, by its connection:cloud:issue reference. */
  ticket: string | null;
  /** The directory a spec-check card checks, and how many gaps it may reproduce. */
  module: string | null;
  reproductions: number | null;
};

/** A lesson of the goal's course, written once from its knowledge notes. */
export type GoalLessonDto = {
  id: string;
  state: 'WRITING' | 'READY' | 'FAILED';
  content: { explanation: string; exercise: string; observe: string; check: string } | null;
  failure: string | null;
};

export function parseGoalLesson(value: unknown): GoalLessonDto {
  const lesson = object(value, 'goal lesson');
  const content = lesson.content === null || lesson.content === undefined ? null : object(lesson.content, 'goal lesson content');
  return {
    id: text(lesson.id, 'goal lesson id'),
    state: oneOf(lesson.state, ['WRITING', 'READY', 'FAILED'] as const, 'goal lesson state'),
    content: content === null ? null : {
      explanation: text(content.explanation, 'goal lesson explanation'),
      exercise: text(content.exercise, 'goal lesson exercise'),
      observe: text(content.observe, 'goal lesson observation'),
      check: text(content.check, 'goal lesson check'),
    },
    failure: optionalText(lesson.failure),
  };
}

/** A card the agent floats under the Goal control: nothing starts until Start, and Not now dismisses it. */
export type GoalRecommendationDto = {
  id: string;
  goalId: string;
  card: GoalCardDto;
  state: 'SHOWN' | 'STARTED' | 'DONE' | 'DISMISSED';
  /** The run Start began. */
  taskId: string | null;
  /** The Goal work a work card's Start made of its issue; the card stays started until that work is done or dropped. */
  workItemId: string | null;
  /** The spec check a spec-check card's Start began. */
  checkId: string | null;
  createdAt: string;
};

/** A gap the goal's last spec check reproduced with a failing test, and the Goal work it became once the user added it. */
export type GoalGapDto = {
  checkId: string;
  candidateId: string;
  module: string;
  title: string;
  rule: string;
  specUrl: string;
  test: { path: string; command: string; exitCode: number } | null;
  /** The issue the check drafted, which only the user files. */
  draft: { title: string; body: string; labels: string[] } | null;
  workItemId: string | null;
  state: WorkItemState | null;
};

/** What a research run learned for a goal, with the pages and files it rests on. */
export type GoalKnowledgeNoteDto = {
  id: string;
  topic: string;
  body: string;
  /** A page address, or owner/repo:path with the commit it was read at. */
  sources: { reference: string; commit: string | null }[];
  savedAt: string;
};

function parseCard(value: unknown): GoalCardDto {
  const card = object(value, 'goal card');
  return {
    kind: oneOf(card.kind, ['RESEARCH', 'LESSON', 'WORK', 'SPEC_CHECK'] as const, 'goal card kind'),
    title: text(card.title, 'goal card title'),
    reason: text(card.reason, 'goal card reason'),
    scope: text(card.scope, 'goal card scope'),
    pages: parseList(card.pages, (entry) => text(entry, 'goal card page'), 'goal card pages'),
    workItemId: optionalText(card.workItemId),
    issue: Number.isSafeInteger(card.issue) && (card.issue as number) > 0 ? card.issue as number : null,
    ticket: optionalText(card.ticket),
    module: optionalText(card.module),
    reproductions: Number.isSafeInteger(card.reproductions) && (card.reproductions as number) > 0 ? card.reproductions as number : null,
  };
}

export function parseGoalRecommendation(value: unknown): GoalRecommendationDto {
  const recommendation = object(value, 'goal recommendation');
  return {
    id: text(recommendation.id, 'goal recommendation id'),
    goalId: text(recommendation.goalId, 'goal recommendation goal'),
    card: parseCard(recommendation.card),
    state: oneOf(recommendation.state, ['SHOWN', 'STARTED', 'DONE', 'DISMISSED'] as const, 'goal recommendation state'),
    taskId: optionalText(recommendation.taskId),
    workItemId: optionalText(recommendation.workItemId),
    checkId: optionalText(recommendation.checkId),
    createdAt: text(recommendation.createdAt, 'goal recommendation time'),
  };
}

export function parseGoalGap(value: unknown): GoalGapDto {
  const gap = object(value, 'goal gap');
  const test = gap.test === null || gap.test === undefined ? null : object(gap.test, 'goal gap test');
  const draft = gap.draft === null || gap.draft === undefined ? null : object(gap.draft, 'goal gap draft');
  return {
    checkId: text(gap.checkId, 'goal gap check'),
    candidateId: text(gap.candidateId, 'goal gap candidate'),
    module: text(gap.module, 'goal gap module'),
    title: text(gap.title, 'goal gap title'),
    rule: typeof gap.rule === 'string' ? gap.rule : '',
    specUrl: typeof gap.specUrl === 'string' ? gap.specUrl : '',
    test: test === null ? null : { path: text(test.path, 'goal gap test path'), command: typeof test.command === 'string' ? test.command : '',
      exitCode: typeof test.exitCode === 'number' ? test.exitCode : -1 },
    draft: draft === null ? null : { title: text(draft.title, 'goal gap draft title'), body: typeof draft.body === 'string' ? draft.body : '',
      labels: parseList(draft.labels ?? [], (label) => text(label, 'goal gap draft label'), 'goal gap draft labels') },
    workItemId: optionalText(gap.workItemId),
    state: gap.state === null || gap.state === undefined ? null : oneOf(gap.state, ['PLANNED', 'WORKING', 'DONE', 'DROPPED'] as const, 'goal gap state'),
  };
}

export function parseGoalKnowledgeNote(value: unknown): GoalKnowledgeNoteDto {
  const note = object(value, 'goal note');
  return {
    id: text(note.id, 'goal note id'),
    topic: text(note.topic, 'goal note topic'),
    body: text(note.body, 'goal note body'),
    sources: parseList(note.sources, (entry) => {
      const source = object(entry, 'goal note source');
      return { reference: text(source.reference, 'goal note source'), commit: optionalText(source.commit) };
    }, 'goal note sources'),
    savedAt: text(note.savedAt, 'goal note time'),
  };
}

/** One message the user sent a goal's agent, and its answer once it arrives. */
export type GoalExchangeDto = {
  id: string;
  goalId: string;
  message: string;
  /** ByteQuay reported an event, such as a finished research run, rather than the user writing the message. */
  automatic: boolean;
  /** The lesson the user asked about. */
  lessonId: string | null;
  inputRevision: number;
  state: 'PENDING' | 'ANSWERED' | 'FAILED';
  reply: {
    assessment: string;
    question: { text: string; suggestions: string[] } | null;
    proposal: GoalProposalDto | null;
    course: GoalCourseDraftDto | null;
    recommendation: GoalCardDto | null;
  } | null;
  failure: string | null;
  askedAt: string;
};

export function parseGoalExchange(value: unknown): GoalExchangeDto {
  const exchange = object(value, 'goal exchange');
  const reply = exchange.reply === null || exchange.reply === undefined ? null : object(exchange.reply, 'goal reply');
  const question = reply === null || reply.question === null || reply.question === undefined ? null : object(reply.question, 'goal question');
  const proposal = reply === null || reply.proposal === null || reply.proposal === undefined ? null : object(reply.proposal, 'goal proposal');
  return {
    id: text(exchange.id, 'goal exchange id'),
    goalId: text(exchange.goalId, 'goal exchange goal'),
    message: text(exchange.message, 'goal exchange message'),
    automatic: exchange.automatic === true,
    lessonId: optionalText(exchange.lessonId),
    inputRevision: revision(exchange.inputRevision, 'goal exchange revision'),
    state: oneOf(exchange.state, ['PENDING', 'ANSWERED', 'FAILED'] as const, 'goal exchange state'),
    reply: reply === null ? null : {
      assessment: text(reply.assessment, 'goal assessment'),
      question: question === null ? null : {
        text: text(question.text, 'goal question'),
        suggestions: parseList(question.suggestions, (entry) => text(entry, 'goal suggestion'), 'goal suggestions'),
      },
      proposal: proposal === null ? null : {
        title: text(proposal.title, 'goal proposal title'),
        objective: text(proposal.objective, 'goal proposal objective'),
        successDefinition: text(proposal.successDefinition, 'goal proposal success'),
        startingKnowledge: text(proposal.startingKnowledge, 'goal proposal knowledge'),
        constraints: text(proposal.constraints, 'goal proposal constraints'),
      },
      course: reply.course === null || reply.course === undefined ? null : {
        parts: parseList(object(reply.course, 'goal course').parts, (entry) => {
          const part = object(entry, 'goal course part');
          return {
            title: text(part.title, 'goal course part title'),
            lessons: parseList(part.lessons, (value) => {
              const lesson = object(value, 'goal lesson');
              return { title: text(lesson.title, 'goal lesson title'), outcome: text(lesson.outcome, 'goal lesson outcome') };
            }, 'goal lessons'),
          };
        }, 'goal course parts'),
      },
      recommendation: reply.recommendation === null || reply.recommendation === undefined ? null : parseCard(reply.recommendation),
    },
    failure: optionalText(exchange.failure),
    askedAt: text(exchange.askedAt, 'goal exchange time'),
  };
}

export function parseList<T>(value: unknown, parse: (entry: unknown) => T, what: string): T[] {
  if (!Array.isArray(value)) throw new Error(`Malformed ${what}`);
  return value.map(parse);
}

export type WorkItemState = 'PLANNED' | 'WORKING' | 'DONE' | 'DROPPED';
export type WorkItemKind = 'LEARN' | 'INVESTIGATE' | 'DEVELOP';
export type CompletionRule = 'MERGED_DELIVERY' | 'ACCEPTED_RESULT';
export type WorkItemDto = {
  id: string;
  workspaceId: string;
  goalId: string | null;
  title: string;
  kind: WorkItemKind;
  acceptanceCriteria: string;
  completionRule: CompletionRule;
  state: WorkItemState;
  revision: number;
  links: GoalItemDto[];
  history: { operationId: string; action: string; detail: string; at: string }[];
  createdAt: string;
  updatedAt: string;
};
export type CreateWorkItem = Pick<WorkItemDto, 'id' | 'goalId' | 'title' | 'kind' | 'acceptanceCriteria' | 'completionRule'>;
export type WorkItemDecision = {
  operationId: string;
  expectedRevision: number;
  action: 'EDIT' | 'BEGIN' | 'ACCEPT' | 'DROP' | 'REOPEN' | 'MOVE';
  title?: string;
  acceptanceCriteria?: string;
  completionRule?: CompletionRule;
  goalId?: string;
  detail: string;
};

export function parseWorkItem(value: unknown): WorkItemDto {
  const item = object(value, 'work item');
  if (!Number.isSafeInteger(item.revision) || (item.revision as number) < 0) throw new Error('Malformed work item revision');
  return {
    id: text(item.id, 'work item id'),
    workspaceId: text(item.workspaceId, 'work item workspace'),
    goalId: optionalText(item.goalId),
    title: text(item.title, 'work item title'),
    kind: oneOf(item.kind, ['LEARN', 'INVESTIGATE', 'DEVELOP'] as const, 'work item kind'),
    acceptanceCriteria: text(item.acceptanceCriteria, 'work item criteria'),
    completionRule: oneOf(item.completionRule, ['MERGED_DELIVERY', 'ACCEPTED_RESULT'] as const, 'completion rule'),
    state: oneOf(item.state, ['PLANNED', 'WORKING', 'DONE', 'DROPPED'] as const, 'work item state'),
    revision: item.revision as number,
    links: parseList(item.links, parseGoalItem, 'work item links'),
    history: parseList(item.history, (value) => {
      const event = object(value, 'work item history');
      return { operationId: text(event.operationId, 'operation'), action: text(event.action, 'action'), detail: text(event.detail, 'detail'), at: text(event.at, 'time') };
    }, 'work item history'),
    createdAt: text(item.createdAt, 'work item created'),
    updatedAt: text(item.updatedAt, 'work item updated'),
  };
}
