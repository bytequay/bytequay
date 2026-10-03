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

import { useCallback, useEffect, useMemo, useState } from 'react';
import { getCached, setCached } from '../dataCache';
import type { Bridge } from '../types';
import type { GoalDto, GoalItemDto, GoalItemKind, WorkItemDto, CreateWorkItem, WorkItemDecision } from './contracts';
import type { GoalRef } from './model';

/** Fired after any goal change so every view of the workspace's goals reads them again. */
export const GOALS_CHANGED = 'bytequay:goals-changed';
/** Items change state as their pull requests, issues, tickets and tasks move; a local read. */
const GOALS_POLL_MS = 15_000;

export type GoalBridge = Pick<Bridge, 'listWorkItems' | 'createWorkItem' | 'decideWorkItem' | 'linkWorkItem' | 'listGoals' | 'createGoal' | 'updateGoal' | 'deleteGoal' | 'addGoalItem' | 'removeGoalItem' | 'searchGoalCandidates'>;

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
  }), [bridge, error, goals, workItems, search, settle, settleWork, workspaceId]);
}
