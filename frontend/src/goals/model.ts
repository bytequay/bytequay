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
import type { GoalDto, GoalItemDto, GoalItemKind, GoalItemState } from './contracts';

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

/** The workspace a Home card belongs to: its task's, its issue's, its ticket's, or the one watching its repository. */
export function attentionWorkspace(item: AttentionItem, workspaces: { id: string; monitoredOwner: string; monitoredRepo: string }[]): string | null {
  if (item.task !== undefined) return item.task.dto.workspaceId;
  if (item.workspaceId !== null) return item.workspaceId;
  if (item.jira !== undefined) return item.jira.workspace?.id ?? null;
  const repo = item.repo.toLowerCase();
  return workspaces.find((workspace) => `${workspace.monitoredOwner}/${workspace.monitoredRepo}`.toLowerCase() === repo)?.id ?? null;
}

export function goalHas(goal: GoalDto | null, ref: GoalRef | null): boolean {
  return goal !== null && ref !== null && goal.items.some((item) => item.kind === ref.kind && item.key === ref.key);
}

/** The items the user has to act on: those still showing in Needs attention. */
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
};

export function summarize(goal: GoalDto, needsYou: ReadonlySet<string>): GoalSummary {
  const count = (state: GoalItemState) => goal.items.filter((item) => item.state === state).length;
  const prs = goal.items.filter((item) => item.kind === 'PULL_REQUEST');
  return {
    total: goal.items.length,
    finished: count('FINISHED'),
    working: count('WORKING'),
    idle: count('IDLE'),
    prsMerged: prs.filter((item) => item.status === 'Merged').length,
    prsOpen: prs.filter((item) => item.state !== 'FINISHED').length,
    needs: goal.items.filter((item) => needsYou.has(refKey(item))).length,
    blocked: goal.items.filter((item) => item.blocked).length,
  };
}

/** Percentages of the bar for finished and working items, with idle filling the rest. */
export function progressWidths(summary: GoalSummary): { finished: string; working: string } {
  const total = Math.max(1, summary.total);
  return { finished: `${(summary.finished / total) * 100}%`, working: `${(summary.working / total) * 100}%` };
}

export const RING_CIRCUMFERENCE = 43.98;

/** The progress ring's dash: the finished share of its circumference. */
export function ringDash(summary: GoalSummary): string {
  return `${(summary.finished / Math.max(1, summary.total)) * RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`;
}

export type GoalTab = 'all' | 'needs' | 'blocked';

export function itemsIn(goal: GoalDto, tab: GoalTab, needsYou: ReadonlySet<string>): GoalItemDto[] {
  if (tab === 'needs') return goal.items.filter((item) => needsYou.has(refKey(item)));
  if (tab === 'blocked') return goal.items.filter((item) => item.blocked);
  return goal.items;
}

export const STATE_GROUPS: { state: GoalItemState; label: string }[] = [
  { state: 'WORKING', label: 'Working' },
  { state: 'IDLE', label: 'Idle' },
  { state: 'FINISHED', label: 'Finished' },
];

/** The latest change ByteQuay knows of on each item, newest first. */
export function recentActivity(goal: GoalDto, limit = 5): { item: GoalItemDto; text: string; at: string }[] {
  return goal.items
    .flatMap((item) => (item.activity === null ? [] : [{ item, text: item.activity.text, at: item.activity.at }]))
    .sort((left, right) => Date.parse(right.at) - Date.parse(left.at))
    .slice(0, limit);
}

/** Search results not already in the goal, at most a handful per query. */
export function addable(candidates: GoalItemDto[], goal: GoalDto, limit = 6): GoalItemDto[] {
  const present = new Set(goal.items.map(refKey));
  return candidates.filter((candidate) => !present.has(refKey(candidate))).slice(0, limit);
}

/** A pill's place in its page: its distance from the page's right and bottom edges. */
export type PillPosition = { right: number; bottom: number };

type Box = { width: number; height: number };
type Edges = { left: number; top: number; right: number; bottom: number };

const PILL_MARGIN = 8;
const PILL_GAP = 10;

/**
 * Where the pill sits: the saved spot, or just above the composer's right edge, kept inside the page
 * and never over the composer. Composer edges are relative to the page.
 */
export function placePill(saved: PillPosition | null, pill: Box, page: Box, composer: Edges | null): PillPosition {
  const above = composer === null ? null : page.height - composer.top + PILL_GAP;
  const start = saved ?? (composer === null ? { right: 24, bottom: 24 } : { right: page.width - composer.right, bottom: above ?? 24 });
  const clamp = (value: number, max: number) => Math.round(Math.max(PILL_MARGIN, Math.min(Math.max(PILL_MARGIN, max), value)));
  const placed = {
    right: clamp(start.right, page.width - pill.width - PILL_MARGIN),
    bottom: clamp(start.bottom, page.height - pill.height - PILL_MARGIN),
  };
  if (composer === null || above === null) return placed;
  const left = page.width - placed.right - pill.width;
  const top = page.height - placed.bottom - pill.height;
  const overlaps = left < composer.right && left + pill.width > composer.left && top < composer.bottom && top + pill.height > composer.top;
  return overlaps ? { right: placed.right, bottom: clamp(above, page.height - pill.height - PILL_MARGIN) } : placed;
}
