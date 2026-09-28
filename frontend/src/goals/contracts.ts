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
export type GoalItemState = 'IDLE' | 'WORKING' | 'FINISHED';

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
  items: GoalItemDto[];
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
    state: oneOf(source.state, ['IDLE', 'WORKING', 'FINISHED'] as const, 'goal item state'),
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
    items: goal.items.map(parseGoalItem),
    createdAt: text(goal.createdAt, 'goal created'),
    updatedAt: text(goal.updatedAt, 'goal updated'),
  };
}

export function parseList<T>(value: unknown, parse: (entry: unknown) => T, what: string): T[] {
  if (!Array.isArray(value)) throw new Error(`Malformed ${what}`);
  return value.map(parse);
}
