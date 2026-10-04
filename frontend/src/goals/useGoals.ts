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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getCached, setCached } from '../dataCache';
import { issueUrl } from '../repository-context/specChecks';
import type { ModelChoice } from '../settings/ModelsPage';
import type { AgentModelSettingDto, Bridge, ModelSettingsDto } from '../types';
import type {
  GoalDto, GoalExchangeDto, GoalGapDto, GoalItemDto, GoalItemKind, GoalKnowledgeNoteDto, GoalLessonDto, GoalRecommendationDto, TopicLevel, WorkItemDto, CreateWorkItem,
  WorkItemDecision,
} from './contracts';
import type { GoalRef } from './model';

/** Fired after any goal change so every view of the workspace's goals reads them again. */
export const GOALS_CHANGED = 'bytequay:goals-changed';
/** Items change state as their pull requests, issues, tickets and tasks move; a local read. */
const GOALS_POLL_MS = 15_000;
/** How often an open conversation reads again while its agent is answering; a local read. */
const ANSWER_POLL_MS = 1_500;

export type GoalBridge = Pick<Bridge, 'listWorkItems' | 'createWorkItem' | 'decideWorkItem' | 'linkWorkItem' | 'listGoals' | 'createGoal' | 'updateGoal' | 'deleteGoal'
  | 'addGoalItem' | 'removeGoalItem' | 'searchGoalCandidates' | 'startGoal' | 'listGoalExchanges' | 'askGoal' | 'retryGoalExchange' | 'stopGoalExchange' | 'reassessGoal' | 'acceptGoalDefinition'
  | 'acceptGoalCourse' | 'startGoalRecommendation' | 'dismissGoalRecommendation' | 'listGoalKnowledge' | 'openGoalLesson' | 'readGoalLesson' | 'finishGoalLesson'
  | 'setGoalTopic' | 'listGoalGaps' | 'addGoalGap' | 'openInAppBrowser' | 'getGoalModel' | 'setGoalModel' | 'clearGoalModel' | 'getModelSettings'>;

/** The model a goal's agent uses, with the configured models to choose from. */
export type GoalModel = { setting: AgentModelSettingDto; settings: ModelSettingsDto };

export type Goals = {
  goals: GoalDto[];
  workItems: WorkItemDto[];
  createWork: (request: CreateWorkItem) => Promise<WorkItemDto>;
  decideWork: (item: WorkItemDto, command: WorkItemDecision) => Promise<WorkItemDto>;
  linkWork: (item: WorkItemDto, reference: GoalItemDto) => Promise<WorkItemDto>;
  /** The goal the workspace's pages follow; null until the workspace has one. */
  pinned: GoalDto | null;
  error: string | null;
  create: (title: string) => Promise<GoalDto>;
  /** Makes the goal the one the pages follow. */
  pin: (goalId: string) => Promise<void>;
  rename: (goalId: string, title: string) => Promise<GoalDto>;
  /** Pauses guidance, so the goal's agent hears no automatic events, or resumes it. */
  pause: (goalId: string, paused: boolean) => Promise<GoalDto>;
  /** Archives the goal; its work and history stay saved. */
  remove: (goalId: string) => Promise<void>;
  add: (goalId: string, kind: GoalItemKind, key: string) => Promise<void>;
  removeItem: (goalId: string, kind: GoalItemKind, key: string) => Promise<void>;
  search: (query: string) => Promise<GoalItemDto[]>;
  /** Starts a goal from the user's first answer; it becomes the one the pages follow. */
  start: (operationId: string, message: string) => Promise<GoalDto>;
  exchanges: (goalId: string) => Promise<GoalExchangeDto[]>;
  ask: (goalId: string, operationId: string, message: string, lessonId?: string | null) => Promise<GoalExchangeDto[]>;
  retry: (goalId: string, exchangeId: string) => Promise<GoalExchangeDto[]>;
  stop: (goalId: string, exchangeId: string) => Promise<GoalExchangeDto[]>;
  reassess: (goalId: string, operationId: string) => Promise<GoalExchangeDto[]>;
  /** Accepts the definition an answer proposed, under the title the user kept or edited. */
  accept: (goalId: string, exchangeId: string, title: string) => Promise<GoalDto>;
  /** Accepts a proposed course: its lessons become the goal's Learn work items. */
  acceptCourse: (goalId: string, exchangeId: string) => Promise<GoalDto>;
  /** Start on a card: the run it recommends begins. */
  startCard: (goalId: string, recommendationId: string) => Promise<GoalRecommendationDto>;
  /** Not now on a card; the agent does not offer it again. */
  dismissCard: (goalId: string, recommendationId: string) => Promise<GoalRecommendationDto>;
  knowledge: (goalId: string) => Promise<GoalKnowledgeNoteDto[]>;
  gaps: (goalId: string) => Promise<GoalGapDto[]>;
  addGap: (goalId: string, candidateId: string) => Promise<GoalGapDto[]>;
  model: (goalId: string) => Promise<GoalModel>;
  /** Gives the goal its own model, or with null makes it follow the workspace's Development model again. */
  chooseModel: (goalId: string, choice: ModelChoice | null) => Promise<AgentModelSettingDto>;
  /** Opens a gap's drafted issue on GitHub's form, for the user to file; answers whether its body was cut and copied to the clipboard instead. */
  fileIssue: (repository: string, draft: NonNullable<GoalGapDto['draft']>) => Promise<boolean>;
  /** Reads the goals again now, as when an answer may have floated a card. */
  refresh: () => void;
  /** Opens a lesson of the goal's course, writing it the first time. */
  openLesson: (goalId: string, lessonId: string) => Promise<GoalLessonDto>;
  readLesson: (goalId: string, lessonId: string) => Promise<GoalLessonDto>;
  finishLesson: (goalId: string, lessonId: string, operationId: string) => Promise<GoalDto>;
  /** The user's own assessment of a topic. */
  setTopic: (goalId: string, topic: string, level: TopicLevel) => Promise<GoalDto>;
};

const NO_EXCHANGES: GoalExchangeDto[] = [];
const NO_NOTES: GoalKnowledgeNoteDto[] = [];
const NO_GAPS: GoalGapDto[] = [];

/** A goal's conversation with its agent, as the panel shows it. */
export type GoalConversation = {
  /** Whether the goal's conversation has been read; until then nobody knows whether it has begun. */
  loaded: boolean;
  exchanges: GoalExchangeDto[];
  /** What the goal's research runs saved. */
  notes: GoalKnowledgeNoteDto[];
  /** The gaps the goal's last spec check reproduced. */
  gaps: GoalGapDto[];
  addGap: (candidateId: string) => Promise<void>;
  error: string | null;
  ask: (operationId: string, message: string, lessonId?: string | null) => Promise<void>;
  retry: (exchangeId: string) => Promise<void>;
  /** Stops the reply the agent is writing to this message. */
  stop: (exchangeId: string) => Promise<void>;
  /** Update now: the agent reassesses the goal from its current facts. */
  reassess: (operationId: string) => Promise<void>;
  accept: (exchangeId: string, title: string) => Promise<void>;
  acceptCourse: (exchangeId: string) => Promise<void>;
  openLesson: (lessonId: string) => Promise<GoalLessonDto>;
  readLesson: (lessonId: string) => Promise<GoalLessonDto>;
  finishLesson: (lessonId: string, operationId: string) => Promise<void>;
  correctTopic: (topic: string, level: TopicLevel) => Promise<void>;
  /** The model the goal's agent uses; null until read, and while it cannot be. */
  model: GoalModel | null;
  chooseModel: (choice: ModelChoice | null) => Promise<void>;
};

const windowBridge = (): GoalBridge => window.bridge;

function announce(workspaceId: string): void {
  window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: workspaceId }));
}

/**
 * The pinned goal of each listed workspace, for a page that spans workspaces such as Home. A
 * workspace whose read fails keeps its last goal.
 */
export function usePinnedGoals(workspaceIds: readonly string[], bridge: () => GoalBridge = windowBridge): ReadonlyMap<string, GoalDto> {
  const key = workspaceIds.join(',');
  const [pinned, setPinned] = useState<ReadonlyMap<string, GoalDto>>(() => new Map(workspaceIds.flatMap((id) => {
    const goal = getCached<GoalDto[]>(`goals:${id}`)?.find((candidate) => candidate.pinned);
    return goal === undefined ? [] : [[id, goal] as const];
  })));
  useEffect(() => {
    const ids = key === '' ? [] : key.split(',');
    if (ids.length === 0) return;
    let active = true;
    const read = (): void => {
      if (document.hidden) return;
      // A read that throws on the spot fails like any other, so goals never take the page down.
      void Promise.allSettled(ids.map((id) => Promise.resolve().then(() => bridge().listGoals(id)).then((goals) => ({ id, goals })))).then((results) => {
        if (!active) return;
        setPinned((last) => {
          const next = new Map(last);
          for (const result of results) {
            if (result.status !== 'fulfilled') continue;
            const goal = result.value.goals.find((candidate) => candidate.pinned);
            if (goal === undefined) next.delete(result.value.id);
            else next.set(result.value.id, goal);
          }
          return next;
        });
      });
    };
    const changed = (event: Event): void => {
      if (ids.includes((event as CustomEvent<string>).detail)) read();
    };
    read();
    window.addEventListener(GOALS_CHANGED, changed);
    const timer = window.setInterval(read, GOALS_POLL_MS);
    return () => { active = false; window.removeEventListener(GOALS_CHANGED, changed); window.clearInterval(timer); };
  }, [bridge, key]);
  return pinned;
}

/** Adds an item to a goal or takes it out, then tells every view of the workspace's goals. */
export function toggleGoalItem(workspaceId: string, goalId: string, ref: GoalRef, inGoal: boolean, bridge: GoalBridge = window.bridge): Promise<void> {
  const change = inGoal ? bridge.removeGoalItem(workspaceId, goalId, ref.kind, ref.key) : bridge.addGoalItem(workspaceId, goalId, ref.kind, ref.key);
  return change.then(() => announce(workspaceId));
}

export function useGoals(workspaceId: string, bridge: () => GoalBridge = windowBridge): Goals {
  const seed = `goals:${workspaceId}`;
  const [goals, setGoals] = useState<GoalDto[]>(() => getCached<GoalDto[]>(seed) ?? []);
  const [workItems, setWorkItems] = useState<WorkItemDto[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    const read = (): void => {
      if (document.hidden) return;
      void Promise.all([bridge().listGoals(workspaceId), bridge().listWorkItems(workspaceId)]).then(([found, work]) => {
        if (!active) return;
        setGoals(found);
        setWorkItems(work);
        setError(null);
      }, (failure: unknown) => {
        if (active) setError(failure instanceof Error ? failure.message : 'Goals could not be loaded');
      });
    };
    const changed = (event: Event): void => {
      if ((event as CustomEvent<string>).detail === workspaceId) read();
    };
    read();
    window.addEventListener(GOALS_CHANGED, changed);
    const timer = window.setInterval(read, GOALS_POLL_MS);
    return () => { active = false; window.removeEventListener(GOALS_CHANGED, changed); window.clearInterval(timer); };
  }, [bridge, workspaceId]);

  // The next visit to any page of the workspace paints these before its own read answers.
  useEffect(() => { setCached(seed, goals); }, [goals, seed]);

  // Each answer is the goal as it now stands; the others re-read on the event.
  const settle = useCallback((goal: GoalDto): GoalDto => {
    setGoals((current) => (current.some((existing) => existing.id === goal.id)
      ? current.map((existing) => (existing.id === goal.id ? goal : existing))
      : [...current, goal]));
    announce(workspaceId);
    return goal;
  }, [workspaceId]);

  // Kept the same across reads, so an open search is not repeated each time the goals refresh.
  const search = useCallback((query: string) => bridge().searchGoalCandidates(workspaceId, query), [bridge, workspaceId]);
  // Kept the same across reads too, so an open conversation is not read again each time the goals refresh.
  const exchanges = useCallback((goalId: string) => bridge().listGoalExchanges(workspaceId, goalId), [bridge, workspaceId]);
  const knowledge = useCallback((goalId: string) => bridge().listGoalKnowledge(workspaceId, goalId), [bridge, workspaceId]);
  const gaps = useCallback((goalId: string) => bridge().listGoalGaps(workspaceId, goalId), [bridge, workspaceId]);
  const model = useCallback((goalId: string) => Promise.all([bridge().getGoalModel(workspaceId, goalId), bridge().getModelSettings(workspaceId)])
    .then(([setting, settings]) => ({ setting, settings })), [bridge, workspaceId]);
  const refresh = useCallback(() => announce(workspaceId), [workspaceId]);
  // Kept the same across reads, so an open lesson is not opened again each time the goals refresh.
  const openLesson = useCallback((goalId: string, lessonId: string) => bridge().openGoalLesson(workspaceId, goalId, lessonId), [bridge, workspaceId]);
  const readLesson = useCallback((goalId: string, lessonId: string) => bridge().readGoalLesson(workspaceId, goalId, lessonId), [bridge, workspaceId]);

  // A card that finished or was dismissed leaves the goal's cards; a started one stays until its run ends.
  const settleCard = useCallback((card: GoalRecommendationDto): GoalRecommendationDto => {
    const live = card.state === 'SHOWN' || card.state === 'STARTED';
    setGoals((current) => current.map((goal) => (goal.id !== card.goalId ? goal
      : { ...goal, recommendations: goal.recommendations.flatMap((existing) => (existing.id !== card.id ? [existing] : live ? [card] : [])) })));
    announce(workspaceId);
    return card;
  }, [workspaceId]);

  const settleWork = useCallback((item: WorkItemDto): WorkItemDto => {
    setWorkItems((current) => [...current.filter((existing) => existing.id !== item.id), item]);
    setGoals((current) => current.map((goal) => ({ ...goal, items: [...goal.items.filter((existing) => existing.id !== item.id), ...(item.goalId === goal.id ? [item] : [])] })));
    announce(workspaceId);
    return item;
  }, [workspaceId]);

  return useMemo(() => ({
    goals,
    workItems,
    createWork: (request) => bridge().createWorkItem(workspaceId, request).then(settleWork),
    decideWork: (item, command) => bridge().decideWorkItem(workspaceId, item.id, command).then(settleWork),
    linkWork: (item, reference) => bridge().linkWorkItem(workspaceId, item.id, item.revision, reference.kind, reference.key).then(settleWork),
    pinned: goals.find((goal) => goal.pinned) ?? null,
    error,
    create: (title) => bridge().createGoal(workspaceId, title, '').then(settle),
    pin: (goalId) => bridge().updateGoal(workspaceId, goalId, { pinned: true }).then(() => announce(workspaceId)),
    rename: (goalId, title) => bridge().updateGoal(workspaceId, goalId, { title }).then(settle),
    pause: (goalId, paused) => bridge().updateGoal(workspaceId, goalId, { paused }).then(settle),
    remove: (goalId) => bridge().deleteGoal(workspaceId, goalId).then(() => {
      setGoals((current) => current.filter((goal) => goal.id !== goalId));
      announce(workspaceId);
    }),
    add: (goalId, kind, key) => bridge().addGoalItem(workspaceId, goalId, kind, key).then(settle).then(() => {}),
    removeItem: (goalId, kind, key) => bridge().removeGoalItem(workspaceId, goalId, kind, key).then(settle).then(() => {}),
    search,
    start: (operationId, message) => bridge().startGoal(workspaceId, operationId, message).then((goal) => {
      // The new goal is pinned, so the one pinned before is not any more.
      setGoals((current) => [...current.filter((existing) => existing.id !== goal.id).map((existing) => ({ ...existing, pinned: false })), goal]);
      announce(workspaceId);
      return goal;
    }),
    exchanges,
    ask: (goalId, operationId, message, lessonId) => bridge().askGoal(workspaceId, goalId, operationId, message, lessonId),
    retry: (goalId, exchangeId) => bridge().retryGoalExchange(workspaceId, goalId, exchangeId),
    stop: (goalId, exchangeId) => bridge().stopGoalExchange(workspaceId, goalId, exchangeId),
    reassess: (goalId, operationId) => bridge().reassessGoal(workspaceId, goalId, operationId),
    accept: (goalId, exchangeId, title) => bridge().acceptGoalDefinition(workspaceId, goalId, exchangeId, title).then(settle),
    acceptCourse: (goalId, exchangeId) => bridge().acceptGoalCourse(workspaceId, goalId, exchangeId).then(settle),
    startCard: (goalId, recommendationId) => bridge().startGoalRecommendation(workspaceId, goalId, recommendationId).then(settleCard),
    dismissCard: (goalId, recommendationId) => bridge().dismissGoalRecommendation(workspaceId, goalId, recommendationId).then(settleCard),
    knowledge,
    gaps,
    addGap: (goalId, candidateId) => bridge().addGoalGap(workspaceId, goalId, candidateId).then((found) => { announce(workspaceId); return found; }),
    model,
    chooseModel: (goalId, choice) => (choice === null ? bridge().clearGoalModel(workspaceId, goalId)
      : bridge().setGoalModel(workspaceId, goalId, choice.configId, choice.model, choice.effort)),
    fileIssue: (repository, draft) => {
      const { url, cut } = issueUrl(repository, draft);
      return (cut ? navigator.clipboard.writeText(draft.body) : Promise.resolve()).then(() => bridge().openInAppBrowser(url)).then(() => cut);
    },
    refresh,
    openLesson,
    readLesson,
    finishLesson: (goalId, lessonId, operationId) => bridge().finishGoalLesson(workspaceId, goalId, lessonId, operationId).then(settle),
    setTopic: (goalId, topic, level) => bridge().setGoalTopic(workspaceId, goalId, topic, level).then(settle),
  }), [bridge, error, exchanges, gaps, goals, knowledge, model, openLesson, readLesson, refresh, workItems, search, settle, settleCard, settleWork, workspaceId]);
}

/**
 * The goal's conversation and notes, read once they are first shown, again whenever the goal's cards change, such as when a research run
 * ends and its agent is told, and every moment while the agent is answering.
 *
 * @param cards a fingerprint of the goal's cards
 */
export function useGoalConversation(goals: Goals, goalId: string | null, shown: boolean, cards = ''): GoalConversation {
  const [read, setRead] = useState<{ goalId: string; exchanges: GoalExchangeDto[]; notes: GoalKnowledgeNoteDto[]; gaps: GoalGapDto[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = read !== null && read.goalId === goalId ? read : null;
  const exchanges = current?.exchanges ?? NO_EXCHANGES;
  const notes = current?.notes ?? NO_NOTES;
  const gapsFound = current?.gaps ?? NO_GAPS;
  const answering = exchanges.some((exchange) => exchange.state === 'PENDING');
  const { exchanges: list, knowledge, gaps: gapsOf, refresh, openLesson, readLesson } = goals;
  const openOne = useCallback((lessonId: string) => (goalId === null ? Promise.reject(new Error('There is no goal')) : openLesson(goalId, lessonId)), [goalId, openLesson]);
  const readOne = useCallback((lessonId: string) => (goalId === null ? Promise.reject(new Error('There is no goal')) : readLesson(goalId, lessonId)), [goalId, readLesson]);
  const wasAnswering = useRef(false);
  const [model, setModel] = useState<(GoalModel & { goalId: string }) | null>(null);
  const { model: modelOf } = goals;

  // A settled answer may have floated a card, which the goals carry.
  useEffect(() => {
    if (wasAnswering.current && !answering) refresh();
    wasAnswering.current = answering;
  }, [answering, refresh]);

  useEffect(() => {
    if (goalId === null || !shown) return;
    let active = true;
    const load = (): void => {
      // A read that throws on the spot fails like any other, so the conversation never takes the page down.
      void Promise.resolve().then(() => Promise.all([list(goalId), knowledge(goalId), gapsOf(goalId)])).then(([found, saved, gaps]) => {
        if (!active) return;
        setRead({ goalId, exchanges: found, notes: saved, gaps });
        setError(null);
      }, (failure: unknown) => {
        if (active) setError(failure instanceof Error ? failure.message : 'The conversation could not be loaded');
      });
    };
    load();
    const timer = answering ? window.setInterval(load, ANSWER_POLL_MS) : undefined;
    return () => { active = false; window.clearInterval(timer); };
  }, [answering, cards, gapsOf, goalId, knowledge, list, shown]);

  useEffect(() => {
    if (goalId === null || !shown) return;
    let active = true;
    // Without its model the goal shows no model control; its agent still answers with the workspace's.
    void Promise.resolve().then(() => modelOf(goalId)).then((found) => { if (active) setModel({ ...found, goalId }); }, () => {});
    return () => { active = false; };
  }, [goalId, modelOf, shown]);

  return useMemo(() => {
    const show = (found: GoalExchangeDto[]): void => {
      if (goalId !== null) setRead((last) => ({ goalId, exchanges: found, notes: last?.goalId === goalId ? last.notes : [], gaps: last?.goalId === goalId ? last.gaps : [] }));
    };
    return {
      loaded: goalId === null || current !== null,
      exchanges,
      notes,
      gaps: gapsFound,
      error,
      addGap: (candidateId) => (goalId === null ? Promise.resolve() : goals.addGap(goalId, candidateId).then((gaps) => {
        setRead((last) => (last === null || last.goalId !== goalId ? last : { ...last, gaps }));
      })),
      ask: (operationId, message, lessonId) => (goalId === null ? Promise.resolve() : goals.ask(goalId, operationId, message, lessonId).then(show)),
      retry: (exchangeId) => (goalId === null ? Promise.resolve() : goals.retry(goalId, exchangeId).then(show)),
      stop: (exchangeId) => (goalId === null ? Promise.resolve() : goals.stop(goalId, exchangeId).then(show)),
      reassess: (operationId) => (goalId === null ? Promise.resolve() : goals.reassess(goalId, operationId).then(show)),
      // Accepting tells the agent, whose answer is read at once.
      accept: (exchangeId, title) => (goalId === null ? Promise.resolve() : goals.accept(goalId, exchangeId, title).then(() => goals.exchanges(goalId)).then(show)),
      acceptCourse: (exchangeId) => (goalId === null ? Promise.resolve() : goals.acceptCourse(goalId, exchangeId).then(() => goals.exchanges(goalId)).then(show)),
      openLesson: openOne,
      readLesson: readOne,
      // Finishing tells the agent, whose answer is read at once.
      finishLesson: (lessonId, operationId) => (goalId === null ? Promise.resolve()
        : goals.finishLesson(goalId, lessonId, operationId).then(() => goals.exchanges(goalId)).then(show)),
      correctTopic: (topic, level) => (goalId === null ? Promise.resolve() : goals.setTopic(goalId, topic, level).then(() => {})),
      model: model !== null && model.goalId === goalId ? model : null,
      chooseModel: (choice) => (goalId === null ? Promise.resolve() : goals.chooseModel(goalId, choice).then((setting) => {
        setModel((last) => (last === null || last.goalId !== goalId ? last : { ...last, setting }));
      })),
    };
  }, [current, error, exchanges, gapsFound, goalId, goals, model, notes, openOne, readOne]);
}
