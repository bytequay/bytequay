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

import type { AttentionItem, AttentionSource } from '../home/attention';
import type { GoalItemDto, GoalItemKind, WorkItemDto, WorkItemState } from './contracts';

export type GoalRef = { kind: GoalItemKind; key: string };

export const GOAL_SOURCES: Record<GoalItemKind, AttentionSource> = { PULL_REQUEST: 'pr', ISSUE: 'issue', JIRA: 'jira', TASK: 'task' };

export function refKey(ref: GoalRef): string {
  return `${ref.kind} ${ref.key}`;
}

export function numberedKey(repo: string, number: number): string {
  return `${repo.toLowerCase()}#${number}`;
}

/** What an attention card is, as a goal item; null for a card no goal can hold. */
export function goalRef(item: AttentionItem): GoalRef | null {
  if (item.task !== undefined) return { kind: 'TASK', key: item.task.dto.id };
  if (item.jira !== undefined) return { kind: 'JIRA', key: `${item.jira.connectionId}:${item.jira.cloudId}:${item.jira.id}` };
  if (item.repo === '' || item.number <= 0) return null;
  return { kind: item.type === 'pr' ? 'PULL_REQUEST' : 'ISSUE', key: numberedKey(item.repo, item.number) };
}

/** The items with activity the user has not acknowledged, snoozed or visited, whether or not Needs attention ranks them. */
export function needsYouKeys(visibleAttention: AttentionItem[]): ReadonlySet<string> {
  return new Set(visibleAttention.map(goalRef).filter((ref): ref is GoalRef => ref !== null).map(refKey));
}

export type GoalSummary = {
  total: number;
  finished: number;
  working: number;
  idle: number;
  prsMerged: number;
  prsOpen: number;
  needs: number;
  blocked: number;
  dropped: number;
};

export function summarize(goal: { items: WorkItemDto[] }, needsYou: ReadonlySet<string>): GoalSummary {
  const count = (state: WorkItemState) => goal.items.filter((item) => item.state === state).length;
  const prs = [...new Map(goal.items.flatMap((item) => item.links).filter((link) => link.kind === 'PULL_REQUEST').map((link) => [refKey(link), link])).values()];
  return {
    total: goal.items.filter((item) => item.state !== 'DROPPED').length,
    finished: count('DONE'),
    working: count('WORKING'),
    idle: count('PLANNED'),
    prsMerged: prs.filter((item) => item.status === 'Merged').length,
    prsOpen: prs.filter((item) => item.state === 'WORKING').length,
    needs: goal.items.filter((item) => itemNeedsYou(item, needsYou)).length,
    blocked: goal.items.filter((item) => viewOf(item, goal.items) === 'blocked').length,
    dropped: count('DROPPED'),
  };
}

/** Percentages of the bar for finished and working items, with idle filling the rest. */
export function progressWidths(summary: GoalSummary): { finished: string; working: string } {
  const total = Math.max(1, summary.total);
  return { finished: `${(summary.finished / total) * 100}%`, working: `${(summary.working / total) * 100}%` };
}

export type WorkView = 'next' | 'working' | 'blocked' | 'done';

/** What keeps open work from moving: the open work it waits on, and its sources that report themselves blocked. */
export function waitingOn(item: WorkItemDto, items: WorkItemDto[]): string[] {
  return [...items.filter((other) => item.blockedBy.includes(other.id) && isOpen(other)).map((other) => other.title),
    ...item.links.filter((link) => link.blocked).map((link) => `${link.label} is ${link.status}`)];
}

export function viewOf(item: WorkItemDto, items: WorkItemDto[]): WorkView {
  if (!isOpen(item)) return 'done';
  if (waitingOn(item, items).length > 0) return 'blocked';
  return item.state === 'WORKING' ? 'working' : 'next';
}

export function isOpen(item: WorkItemDto): boolean {
  return item.state === 'PLANNED' || item.state === 'WORKING';
}

export function itemNeedsYou(item: WorkItemDto, needsYou: ReadonlySet<string>): boolean {
  return item.links.some((link) => needsYou.has(refKey(link)));
}

/** Search results not already in the work, at most a handful per query. */
export function addable(candidates: GoalItemDto[], goal: { items: WorkItemDto[] }, limit = 6): GoalItemDto[] {
  const present = new Set(goal.items.flatMap((item) => item.links).map(refKey));
  return candidates.filter((candidate) => !present.has(refKey(candidate))).slice(0, limit);
}
