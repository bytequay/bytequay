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

export type GoalDto = {
  id: string;
  workspaceId: string;
  title: string;
  description: string;
  pinned: boolean;
  /** The issue or Jira ticket marked as a Goal that this goal stands for. */
  source: { kind: GoalItemKind; key: string } | null;
  items: WorkItemDto[];
  createdAt: string;
  updatedAt: string;
};

export type GoalPatch = { title?: string; description?: string; pinned?: true };

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

export function parseGoal(value: unknown): GoalDto {
  const goal = object(value, 'goal');
  const origin = goal.source === null || goal.source === undefined ? null : object(goal.source, 'goal source');
  if (typeof goal.pinned !== 'boolean') throw new Error('Malformed goal pinned');
  if (!Array.isArray(goal.items)) throw new Error('Malformed goal items');
  return {
    id: text(goal.id, 'goal id'),
    workspaceId: text(goal.workspaceId, 'goal workspace'),
    title: text(goal.title, 'goal title'),
    description: text(goal.description, 'goal description'),
    pinned: goal.pinned,
    source: origin === null ? null : { kind: oneOf(origin.kind, GOAL_ITEM_KINDS, 'goal source kind'), key: text(origin.key, 'goal source key') },
    items: goal.items.map(parseWorkItem),
    createdAt: text(goal.createdAt, 'goal created'),
    updatedAt: text(goal.updatedAt, 'goal updated'),
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
