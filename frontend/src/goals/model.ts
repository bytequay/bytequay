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
import type {
  GoalCardDto, GoalCourseDraftDto, GoalDto, GoalExchangeDto, GoalGapDto, GoalItemDto, GoalItemKind, GoalKnowledgeNoteDto, GoalProposalDto, WorkItemState,
  WorkItemDto,
} from './contracts';

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
  return goal !== null && ref !== null && goal.items.some((item) => item.links.some((link) => link.kind === ref.kind && link.key === ref.key));
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

export function summarize(goal: GoalDto, needsYou: ReadonlySet<string>): GoalSummary {
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
    blocked: goal.items.filter((item) => item.links.some((link) => link.blocked)).length,
    dropped: count('DROPPED'),
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

export function itemsIn(goal: GoalDto, tab: GoalTab, needsYou: ReadonlySet<string>): WorkItemDto[] {
  if (tab === 'needs') return goal.items.filter((item) => itemNeedsYou(item, needsYou));
  if (tab === 'blocked') return goal.items.filter((item) => item.links.some((link) => link.blocked));
  return goal.items;
}

export const STATE_GROUPS: { state: WorkItemState; label: string }[] = [
  { state: 'WORKING', label: 'Working' },
  { state: 'PLANNED', label: 'Planned' },
  { state: 'DONE', label: 'Done' },
  { state: 'DROPPED', label: 'Dropped' },
];

export function itemNeedsYou(item: WorkItemDto, needsYou: ReadonlySet<string>): boolean {
  return item.links.some((link) => needsYou.has(refKey(link)));
}

/** Decisions remain visible even when the linked task or source is no longer stored. */
export function recentActivity(goal: GoalDto, limit = 5): { item: WorkItemDto; text: string; at: string }[] {
  return goal.items.flatMap((item) => item.history.map((event) => ({ item, text: event.detail, at: event.at })))
    .sort((left, right) => Date.parse(right.at) - Date.parse(left.at)).slice(0, limit);
}

/** Search results not already in the goal, at most a handful per query. */
export function addable(candidates: GoalItemDto[], goal: Pick<GoalDto, 'items'>, limit = 6): GoalItemDto[] {
  const present = new Set(goal.items.flatMap((item) => item.links).map(refKey));
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

/** Starting points for "What do you want to achieve?", written from the workspace's repository without asking a model. */
export function goalDirections(repository: string): string[] {
  const name = repository.slice(repository.lastIndexOf('/') + 1) || repository;
  return [`Understand how ${name} works, one area at a time`, `Fix real bugs in ${name} and get them merged`, `Review ${name} pull requests with confidence`];
}

/** The definition the user can still accept: the newest answer's, unless it is the one already accepted. */
export function openProposal(goal: GoalDto | null, exchanges: GoalExchangeDto[]): { exchangeId: string; proposal: GoalProposalDto } | null {
  const last = exchanges.at(-1);
  if (last === undefined || last.reply === null || last.reply.proposal === null || goal?.definition?.exchangeId === last.id) return null;
  return { exchangeId: last.id, proposal: last.reply.proposal };
}

/** Where a note's source is read: the page itself, or the file on GitHub at the commit the research read it at. */
export function sourceUrl(source: GoalKnowledgeNoteDto['sources'][number]): string {
  if (source.commit === null) return source.reference;
  const colon = source.reference.indexOf(':');
  return `https://github.com/${source.reference.slice(0, colon)}/blob/${source.commit}/${source.reference.slice(colon + 1)}`;
}

/** The course the user can still accept: the newest answer's, the goal's first or a revision of the accepted one. */
export function openCourse(goal: GoalDto | null, exchanges: GoalExchangeDto[]): { exchangeId: string; course: GoalCourseDraftDto } | null {
  const last = exchanges.at(-1);
  if (goal === null || last === undefined || last.reply === null || last.reply.course === null || goal.course?.exchangeId === last.id) return null;
  return { exchangeId: last.id, course: last.reply.course };
}

/** What a started card is doing, as the panel names it. */
export const STARTED_LABELS: Record<GoalCardDto['kind'], string> = { RESEARCH: 'Researching', LESSON: 'Learning', WORK: 'Practicing', SPEC_CHECK: 'Checking' };

/**
 * Where a goal stands, in one line, and how many decisions wait for the user: a proposal or a course to accept, a merged result to accept
 * and a reproduced gap to add or dismiss. Read from what the panel already holds; no model is asked.
 */
export type GoalSituation = { text: string; decisions: number };

export function situation(goal: GoalDto, exchanges: GoalExchangeDto[], gaps: GoalGapDto[]): GoalSituation {
  const open = goal.items.filter((item) => item.state !== 'DONE' && item.state !== 'DROPPED');
  const merged = open.filter((item) => item.links.some((link) => link.kind === 'PULL_REQUEST' && link.state === 'MERGED'));
  const proposal = openProposal(goal, exchanges);
  const course = openCourse(goal, exchanges);
  const unadded = gaps.filter((gap) => gap.workItemId === null);
  const decisions = (proposal === null ? 0 : 1) + (course === null ? 0 : 1) + merged.length + unadded.length;
  return { text: situationText(goal, exchanges, { proposal: proposal !== null, course: course !== null, merged, unadded: unadded.length, open }), decisions };
}

function situationText(goal: GoalDto, exchanges: GoalExchangeDto[], waiting: { proposal: boolean; course: boolean; merged: WorkItemDto[]; unadded: number; open: WorkItemDto[] }): string {
  const last = exchanges.at(-1);
  if (last?.state === 'PENDING') return 'Waiting for the agent';
  if (last?.state === 'FAILED') return 'The agent’s last reply failed; ask again';
  if (waiting.proposal) return 'Your decision: the proposed goal';
  if (waiting.course) return goal.course === null ? 'Your decision: the proposed course' : 'Your decision: the revised course';
  if (waiting.merged.length > 0) return `Your decision: accept ${waiting.merged[0].title}`;
  if (waiting.unadded > 0) return `Your decision: ${waiting.unadded === 1 ? 'a reproduced gap' : `${waiting.unadded} reproduced gaps`}`;
  if (goal.definition === null) return 'Clarifying your goal';
  const started = goal.recommendations.find((card) => card.state === 'STARTED');
  if (started !== undefined) return `${STARTED_LABELS[started.card.kind]}: ${started.card.title}`;
  if (goal.items.length > 0 && waiting.open.length === 0) return 'Ready to conclude';
  const items = new Map(goal.items.map((item) => [item.id, item]));
  const lesson = (goal.course?.parts ?? []).flatMap((part) => part.lessons).map((id) => items.get(id))
    .find((item) => item !== undefined && item.state !== 'DONE' && item.state !== 'DROPPED');
  if (lesson !== undefined) return `Next lesson: ${lesson.title}`;
  if (waiting.open.length > 0) return `Working on ${waiting.open.length === 1 ? 'one work item' : `${waiting.open.length} work items`}`;
  return 'Defined; the agent suggests what comes next';
}

