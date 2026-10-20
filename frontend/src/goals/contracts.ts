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
import { GOAL_ITEM_KINDS, parseList, parseWorkItem } from '../workitems/contracts';
import type { GoalItemKind, WorkItemDto, WorkItemPriority, WorkItemState } from '../workitems/contracts';

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
  /** An informational issue or Jira reference; Epic marking does not control this Goal. */
  source: { kind: GoalItemKind; key: string } | null;
  definition: GoalDefinitionDto | null;
  /** Counts changes to the title, objective and definition; a proposal built from an older revision cannot be accepted. */
  revision: number;
  /**
   * The learning path the user accepted: parts of ordered lessons, each a Learn work item of the goal, and the map of the concepts it
   * teaches, drawn before its first chapter.
   */
  course: { exchangeId: string; parts: { title: string; lessons: string[] }[]; acceptedAt: string; map: LessonDiagramDto | null } | null;
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
      map: course.map === null || course.map === undefined ? null : parseLessonDiagram(course.map),
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
/** A proposed course; a revision of the accepted one keeps a lesson by its id and says why it changes. */
export type GoalCourseDraftDto = { parts: { title: string; lessons: { title: string; outcome: string; lessonId: string | null }[] }[]; reason: string | null };

/**
 * What a card under the Goal control offers: research reads its scope and pages, a lesson card opens its lesson, and a work card opens the
 * composer of its issue with the scope as the instruction.
 */
export type GoalCardDto = {
  kind: 'RESEARCH' | 'LESSON' | 'WORK' | 'SPEC_CHECK' | 'INVESTIGATE';
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
  /** What the agent is unsure of, and any doubt ByteQuay found reading the source again. */
  uncertainty?: string | null;
  /** What the user should know or have done first, such as a lesson to finish. */
  prerequisites?: string | null;
  /** When the work is done; a started work or investigation card's work is accepted against them, or else against its scope. */
  criteria?: string | null;
};

/** A lesson of the goal's course, written once from its knowledge notes. */
/**
 * A small picture the app draws: FLOW for steps in order, TREE for a hierarchy whose first node is the root, COMPONENTS for the parts of a
 * system in the columns of their groups and the arrows between them, SEQUENCE for messages between participants over time.
 */
export type LessonDiagramDto = {
  kind: 'FLOW' | 'TREE' | 'COMPONENTS' | 'SEQUENCE';
  caption: string;
  nodes: { id: string; label: string; note: string | null; parent: string | null; group: string | null }[];
  edges: { from: string; to: string; label: string | null }[];
};

export type LessonSectionDto = { title: string; body: string; diagram: LessonDiagramDto | null };

/** A lesson is a chapter: a summary, the invariants to remember and short sections with diagrams, then its exercise and check. */
export type LessonContentDto = {
  summary: string | null;
  invariants: string[];
  sections: LessonSectionDto[];
  /** The Markdown a lesson written before chapters has instead of a summary and sections. */
  explanation: string | null;
  exercise: string;
  observe: string;
  check: string;
};

export type GoalLessonDto = {
  id: string;
  state: 'WRITING' | 'READY' | 'FAILED';
  content: LessonContentDto | null;
  failure: string | null;
};

function parseLessonDiagram(value: unknown): LessonDiagramDto {
  const diagram = object(value, 'lesson diagram');
  return {
    kind: oneOf(diagram.kind, ['FLOW', 'TREE', 'COMPONENTS', 'SEQUENCE'] as const, 'lesson diagram kind'),
    caption: text(diagram.caption, 'lesson diagram caption'),
    nodes: parseList(diagram.nodes, (entry) => {
      const node = object(entry, 'lesson diagram node');
      return { id: text(node.id, 'lesson diagram node id'), label: text(node.label, 'lesson diagram node label'), note: optionalText(node.note),
        parent: optionalText(node.parent), group: optionalText(node.group) };
    }, 'lesson diagram nodes'),
    edges: parseList(diagram.edges, (entry) => {
      const edge = object(entry, 'lesson diagram edge');
      return { from: text(edge.from, 'lesson diagram edge from'), to: text(edge.to, 'lesson diagram edge to'), label: optionalText(edge.label) };
    }, 'lesson diagram edges'),
  };
}

export function parseGoalLesson(value: unknown): GoalLessonDto {
  const lesson = object(value, 'goal lesson');
  const content = lesson.content === null || lesson.content === undefined ? null : object(lesson.content, 'goal lesson content');
  return {
    id: text(lesson.id, 'goal lesson id'),
    state: oneOf(lesson.state, ['WRITING', 'READY', 'FAILED'] as const, 'goal lesson state'),
    content: content === null ? null : {
      summary: optionalText(content.summary),
      invariants: parseList(content.invariants, (entry) => text(entry, 'lesson invariant'), 'lesson invariants'),
      sections: parseList(content.sections, (entry) => {
        const section = object(entry, 'lesson section');
        return { title: text(section.title, 'lesson section title'), body: text(section.body, 'lesson section body'),
          diagram: section.diagram === null || section.diagram === undefined ? null : parseLessonDiagram(section.diagram) };
      }, 'lesson sections'),
      explanation: optionalText(content.explanation),
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
  /** A file it cites changed at the monitored head since the research read it. */
  stale: boolean;
  /** The repository memory the user made of it with Remember, while that memory is kept. */
  memoryId?: string | null;
};

function parseCard(value: unknown): GoalCardDto {
  const card = object(value, 'goal card');
  return {
    kind: oneOf(card.kind, ['RESEARCH', 'LESSON', 'WORK', 'SPEC_CHECK', 'INVESTIGATE'] as const, 'goal card kind'),
    title: text(card.title, 'goal card title'),
    reason: text(card.reason, 'goal card reason'),
    scope: text(card.scope, 'goal card scope'),
    pages: parseList(card.pages, (entry) => text(entry, 'goal card page'), 'goal card pages'),
    workItemId: optionalText(card.workItemId),
    issue: Number.isSafeInteger(card.issue) && (card.issue as number) > 0 ? card.issue as number : null,
    ticket: optionalText(card.ticket),
    module: optionalText(card.module),
    reproductions: Number.isSafeInteger(card.reproductions) && (card.reproductions as number) > 0 ? card.reproductions as number : null,
    ...(typeof card.uncertainty === 'string' ? { uncertainty: card.uncertainty } : {}),
    ...(typeof card.prerequisites === 'string' ? { prerequisites: card.prerequisites } : {}),
    ...(typeof card.criteria === 'string' ? { criteria: card.criteria } : {}),
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
    stale: note.stale === true,
    ...(typeof note.memoryId === 'string' ? { memoryId: note.memoryId } : {}),
  };
}

/**
 * A change the agent proposes to the goal's open work: new priorities, the whole list of open work an item waits on, and a new order of every
 * open item, with its reason. A field left null keeps what the work has.
 */
export type GoalPlanChangeDto = {
  changes: { workItemId: string; priority: WorkItemPriority | null; waitsOn: string[] | null; acceptanceCriteria: string | null }[];
  order: string[] | null;
  reason: string;
};

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
    plan: GoalPlanChangeDto | null;
    recommendation: GoalCardDto | null;
  } | null;
  failure: string | null;
  askedAt: string;
  /** What the answer rested on; older answers lack it. */
  coverage?: GoalCoverageDto | null;
};

/** What an answer rested on: notes read in full and by topic only, the user's questions, and the practice candidates out of the open issues. */
export type GoalCoverageDto = { notesInFull: number; notesByTopic: number; questions: number; candidates: number; issuesOpen: number };

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
              return { title: text(lesson.title, 'goal lesson title'), outcome: text(lesson.outcome, 'goal lesson outcome'), lessonId: optionalText(lesson.lessonId) };
            }, 'goal lessons'),
          };
        }, 'goal course parts'),
        reason: optionalText(object(reply.course, 'goal course').reason),
      },
      plan: reply.plan === null || reply.plan === undefined ? null : parsePlanChange(reply.plan),
      recommendation: reply.recommendation === null || reply.recommendation === undefined ? null : parseCard(reply.recommendation),
    },
    failure: optionalText(exchange.failure),
    askedAt: text(exchange.askedAt, 'goal exchange time'),
    ...(exchange.coverage === null || exchange.coverage === undefined ? {} : { coverage: parseCoverage(exchange.coverage) }),
  };
}

function parseCoverage(value: unknown): GoalCoverageDto {
  const coverage = object(value, 'goal coverage');
  return {
    notesInFull: revision(coverage.notesInFull, 'goal coverage notes'),
    notesByTopic: revision(coverage.notesByTopic, 'goal coverage topics'),
    questions: revision(coverage.questions, 'goal coverage questions'),
    candidates: revision(coverage.candidates, 'goal coverage candidates'),
    issuesOpen: revision(coverage.issuesOpen, 'goal coverage issues'),
  };
}

function parsePlanChange(value: unknown): GoalPlanChangeDto {
  const plan = object(value, 'goal plan change');
  const ids = (list: unknown): string[] | null => (list === null || list === undefined ? null : parseList(list, (id) => text(id, 'goal plan work item'), 'goal plan work'));
  return {
    changes: plan.changes === null || plan.changes === undefined ? [] : parseList(plan.changes, (entry) => {
      const change = object(entry, 'goal plan work change');
      return {
        workItemId: text(change.workItemId, 'goal plan work item'),
        priority: change.priority === null || change.priority === undefined ? null : oneOf(change.priority, ['HIGH', 'NORMAL', 'LOW'] as const, 'goal plan priority'),
        waitsOn: ids(change.waitsOn),
        acceptanceCriteria: optionalText(change.acceptanceCriteria),
      };
    }, 'goal plan changes'),
    order: ids(plan.order),
    reason: text(plan.reason, 'goal plan reason'),
  };
}
