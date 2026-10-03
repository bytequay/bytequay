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
import type { Bridge } from '../types';
import type {
  GoalDto, GoalExchangeDto, GoalItemDto, GoalItemKind, GoalKnowledgeNoteDto, GoalRecommendationDto, WorkItemDto, CreateWorkItem, WorkItemDecision,
} from './contracts';
import type { GoalRef } from './model';

/** Fired after any goal change so every view of the workspace's goals reads them again. */
export const GOALS_CHANGED = 'bytequay:goals-changed';
/** Items change state as their pull requests, issues, tickets and tasks move; a local read. */
const GOALS_POLL_MS = 15_000;
/** How often an open conversation reads again while its agent is answering; a local read. */
const ANSWER_POLL_MS = 1_500;

export type GoalBridge = Pick<Bridge, 'listWorkItems' | 'createWorkItem' | 'decideWorkItem' | 'linkWorkItem' | 'listGoals' | 'createGoal' | 'updateGoal' | 'deleteGoal'
  | 'addGoalItem' | 'removeGoalItem' | 'searchGoalCandidates' | 'startGoal' | 'listGoalExchanges' | 'askGoal' | 'retryGoalExchange' | 'acceptGoalDefinition'
  | 'acceptGoalCourse' | 'startGoalRecommendation' | 'dismissGoalRecommendation' | 'listGoalKnowledge'>;

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
  remove: (goalId: string) => Promise<void>;
  add: (goalId: string, kind: GoalItemKind, key: string) => Promise<void>;
  removeItem: (goalId: string, kind: GoalItemKind, key: string) => Promise<void>;
  search: (query: string) => Promise<GoalItemDto[]>;
  /** Starts a goal from the user's first answer; it becomes the one the pages follow. */
  start: (operationId: string, message: string) => Promise<GoalDto>;
  exchanges: (goalId: string) => Promise<GoalExchangeDto[]>;
  ask: (goalId: string, operationId: string, message: string) => Promise<GoalExchangeDto[]>;
  retry: (goalId: string, exchangeId: string) => Promise<GoalExchangeDto[]>;
  /** Accepts the definition an answer proposed, under the title the user kept or edited. */
  accept: (goalId: string, exchangeId: string, title: string) => Promise<GoalDto>;
  /** Accepts a proposed course: its lessons become the goal's Learn work items. */
  acceptCourse: (goalId: string, exchangeId: string) => Promise<GoalDto>;
  /** Start on a card: the run it recommends begins. */
  startCard: (goalId: string, recommendationId: string) => Promise<GoalRecommendationDto>;
  /** Not now on a card; the agent does not offer it again. */
  dismissCard: (goalId: string, recommendationId: string) => Promise<GoalRecommendationDto>;
  knowledge: (goalId: string) => Promise<GoalKnowledgeNoteDto[]>;
  /** Reads the goals again now, as when an answer may have floated a card. */
  refresh: () => void;
};

const NO_EXCHANGES: GoalExchangeDto[] = [];
const NO_NOTES: GoalKnowledgeNoteDto[] = [];

/** A goal's conversation with its agent, as the panel shows it. */
export type GoalConversation = {
  exchanges: GoalExchangeDto[];
  /** What the goal's research runs saved. */
  notes: GoalKnowledgeNoteDto[];
  error: string | null;
  ask: (operationId: string, message: string) => Promise<void>;
  retry: (exchangeId: string) => Promise<void>;
  accept: (exchangeId: string, title: string) => Promise<void>;
  acceptCourse: (exchangeId: string) => Promise<void>;
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
  const refresh = useCallback(() => announce(workspaceId), [workspaceId]);

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
    ask: (goalId, operationId, message) => bridge().askGoal(workspaceId, goalId, operationId, message),
    retry: (goalId, exchangeId) => bridge().retryGoalExchange(workspaceId, goalId, exchangeId),
    accept: (goalId, exchangeId, title) => bridge().acceptGoalDefinition(workspaceId, goalId, exchangeId, title).then(settle),
    acceptCourse: (goalId, exchangeId) => bridge().acceptGoalCourse(workspaceId, goalId, exchangeId).then(settle),
    startCard: (goalId, recommendationId) => bridge().startGoalRecommendation(workspaceId, goalId, recommendationId).then(settleCard),
    dismissCard: (goalId, recommendationId) => bridge().dismissGoalRecommendation(workspaceId, goalId, recommendationId).then(settleCard),
    knowledge,
    refresh,
  }), [bridge, error, exchanges, goals, knowledge, refresh, workItems, search, settle, settleCard, settleWork, workspaceId]);
}

/**
 * The goal's conversation and notes, read once they are first shown, again whenever the goal's cards change, such as when a research run
 * ends and its agent is told, and every moment while the agent is answering.
 *
 * @param cards a fingerprint of the goal's cards
 */
export function useGoalConversation(goals: Goals, goalId: string | null, shown: boolean, cards = ''): GoalConversation {
  const [read, setRead] = useState<{ goalId: string; exchanges: GoalExchangeDto[]; notes: GoalKnowledgeNoteDto[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = read !== null && read.goalId === goalId ? read : null;
  const exchanges = current?.exchanges ?? NO_EXCHANGES;
  const notes = current?.notes ?? NO_NOTES;
  const answering = exchanges.some((exchange) => exchange.state === 'PENDING');
  const { exchanges: list, knowledge, refresh } = goals;
  const wasAnswering = useRef(false);

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
      void Promise.resolve().then(() => Promise.all([list(goalId), knowledge(goalId)])).then(([found, saved]) => {
        if (!active) return;
        setRead({ goalId, exchanges: found, notes: saved });
        setError(null);
      }, (failure: unknown) => {
        if (active) setError(failure instanceof Error ? failure.message : 'The conversation could not be loaded');
      });
    };
    load();
    const timer = answering ? window.setInterval(load, ANSWER_POLL_MS) : undefined;
    return () => { active = false; window.clearInterval(timer); };
  }, [answering, cards, goalId, knowledge, list, shown]);

  return useMemo(() => {
    const show = (found: GoalExchangeDto[]): void => { if (goalId !== null) setRead((last) => ({ goalId, exchanges: found, notes: last?.goalId === goalId ? last.notes : [] })); };
    return {
      exchanges,
      notes,
      error,
      ask: (operationId, message) => (goalId === null ? Promise.resolve() : goals.ask(goalId, operationId, message).then(show)),
      retry: (exchangeId) => (goalId === null ? Promise.resolve() : goals.retry(goalId, exchangeId).then(show)),
      // Accepting tells the agent, whose answer is read at once.
      accept: (exchangeId, title) => (goalId === null ? Promise.resolve() : goals.accept(goalId, exchangeId, title).then(() => goals.exchanges(goalId)).then(show)),
      acceptCourse: (exchangeId) => (goalId === null ? Promise.resolve() : goals.acceptCourse(goalId, exchangeId).then(() => goals.exchanges(goalId)).then(show)),
    };
  }, [error, exchanges, goalId, goals, notes]);
}
