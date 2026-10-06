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
  GoalCardDto, GoalCourseDraftDto, GoalDto, GoalExchangeDto, GoalGapDto, GoalItemDto, GoalItemKind, GoalKnowledgeNoteDto, GoalProposalDto, WorkItemDto,
  WorkItemPriority, WorkItemState,
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

/**
 * Whether a card under the Goal control offers practice on this issue or ticket of the goal's repository, so the source's own attention card
 * would only repeat the same next action.
 */
export function goalPicked(goal: GoalDto | null, repository: string, ref: GoalRef | null): boolean {
  return goal !== null && ref !== null && goal.recommendations.some(({ state, card }) => state === 'SHOWN' && card.kind === 'WORK'
    && (card.issue !== null ? ref.kind === 'ISSUE' && ref.key === numberedKey(repository, card.issue) : ref.kind === 'JIRA' && ref.key === card.ticket));
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

export function summarize(goal: Pick<GoalDto, 'items'>, needsYou: ReadonlySet<string>): GoalSummary {
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

export const RING_CIRCUMFERENCE = 43.98;

/** The progress ring's dash: the finished share of its circumference. */
export function ringDash(summary: GoalSummary): string {
  return `${(summary.finished / Math.max(1, summary.total)) * RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`;
}

export type WorkView = 'next' | 'working' | 'blocked' | 'done';

export const WORK_VIEWS: { view: WorkView; label: string; hint: string }[] = [
  { view: 'next', label: 'Next', hint: 'Ready work: your priority first, then work others wait on, then the Goal\'s order' },
  { view: 'working', label: 'Working', hint: 'Work in progress' },
  { view: 'blocked', label: 'Blocked', hint: 'Open work waiting on other work or on a blocked source' },
  { view: 'done', label: 'Done', hint: 'Done and dropped work, latest decision first' },
];

/** What keeps open work from moving: the open work of its Goal it waits on, and its sources that report themselves blocked. */
export function waitingOn(item: WorkItemDto, items: WorkItemDto[]): string[] {
  return [...items.filter((other) => item.blockedBy.includes(other.id) && isOpen(other)).map((other) => other.title),
    ...item.links.filter((link) => link.blocked).map((link) => `${link.label} is ${link.status}`)];
}

/** The open work of the Goal waiting on this item. */
export function unblocks(item: WorkItemDto, items: WorkItemDto[]): WorkItemDto[] {
  return items.filter((other) => isOpen(other) && other.blockedBy.includes(item.id));
}

export function viewOf(item: WorkItemDto, items: WorkItemDto[]): WorkView {
  if (!isOpen(item)) return 'done';
  if (waitingOn(item, items).length > 0) return 'blocked';
  return item.state === 'WORKING' ? 'working' : 'next';
}

const PRIORITY_FIRST: Record<WorkItemPriority, number> = { HIGH: 0, NORMAL: 1, LOW: 2 };

/**
 * The work of one view, from {@code items} judged against all the Goal's work. Next puts the user's priority first, then work others wait
 * on, then the Goal's order; Working and Blocked keep the order; Done shows the latest decision first.
 */
export function workIn(items: WorkItemDto[], view: WorkView, all: WorkItemDto[] = items): WorkItemDto[] {
  const rows = inOrder(items.filter((item) => viewOf(item, all) === view));
  if (view === 'next') {
    return rows.sort((left, right) => PRIORITY_FIRST[left.priority] - PRIORITY_FIRST[right.priority] || unblocks(right, all).length - unblocks(left, all).length);
  }
  return view === 'done' ? rows.sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt)) : rows;
}

export type WorkSource = 'issue' | 'jira' | 'none';

export function sourceOf(item: WorkItemDto): WorkSource {
  return item.links.some((link) => link.kind === 'ISSUE') ? 'issue' : item.links.some((link) => link.kind === 'JIRA') ? 'jira' : 'none';
}

/** A goal's work in the order the user gave it; equal places keep creation order. */
export function inOrder(items: WorkItemDto[]): WorkItemDto[] {
  return [...items].sort((left, right) => left.rank - right.rank || Date.parse(left.createdAt) - Date.parse(right.createdAt));
}

export function isOpen(item: WorkItemDto): boolean {
  return item.state === 'PLANNED' || item.state === 'WORKING';
}

export function itemNeedsYou(item: WorkItemDto, needsYou: ReadonlySet<string>): boolean {
  return item.links.some((link) => needsYou.has(refKey(link)));
}

/** Decisions remain visible even when the linked task or source is no longer stored. */
/** How the Goal's history names each kind of event its work records. */
const EVENT_KINDS: Record<string, string> = { CREATED: 'Added', BEGIN: 'Started', ACCEPT: 'Accepted', DROP: 'Dropped', REOPEN: 'Reopened', EDIT: 'Scope changed',
  MOVE: 'Moved', PRIORITIZE: 'Priority', BLOCK: 'Waits on', UNBLOCK: 'Stopped waiting', LINKED: 'Linked', SUMMARY: 'Summary', LEFT_EPIC: 'Left the Epic' };

/** A receipt says what it records by its operation: a task's turn, or a change in the real status of the work's pull request. */
const RECEIPT_KINDS: [string, string][] = [['turn:', 'Task turn'], ['merged:', 'Merged'], ['opened:', 'Opened'], ['approved:', 'Approved'],
  ['changes:', 'Changes requested'], ['checks:', 'Checks failed']];

/** Every decision and verified event of the goal's work, newest first, with its kind, its work and what it says. */
export function goalHistory(goal: GoalDto): { item: WorkItemDto; kind: string; text: string; at: string }[] {
  return goal.items.flatMap((item) => item.history.map((event) => ({ item, kind: event.action === 'RECEIPT'
    ? RECEIPT_KINDS.find(([prefix]) => event.operationId.startsWith(prefix))?.[1] ?? 'Receipt' : EVENT_KINDS[event.action] ?? event.action.toLowerCase(),
    // A summary is read in full on its work.
    text: event.action === 'SUMMARY' ? '' : event.action === 'ACCEPT' ? acceptedResult(event.detail) : event.detail, at: event.at })))
    .sort((left, right) => Date.parse(right.at) - Date.parse(left.at));
}

/** What concluded work achieved, in the user's words with the merged pull requests observed, or why it was dropped; nothing for open work. */
export function outcomeOf(item: WorkItemDto): string | null {
  const last = item.history.filter((event) => event.action === (item.state === 'DONE' ? 'ACCEPT' : 'DROP')).at(-1);
  if (isOpen(item) || last === undefined) return null;
  return item.state === 'DONE' ? acceptedResult(last.detail) : last.detail;
}

/** An accepted result without its first line, which repeats the criteria it was accepted against. */
function acceptedResult(detail: string): string {
  return detail.split('\n').slice(1).join(' ').trim() || detail;
}

/**
 * The request Goal work opens the task composer with, for the user to review and send: what to do (its card's scope, or else the work's
 * title), when it is done, what the agent was unsure of, and the goal it serves with the goal's constraints.
 */
export function preparedRequest(goal: GoalDto | null, item: WorkItemDto | null, card: GoalCardDto | null): string {
  const instruction = (card?.scope ?? item?.title ?? '').trim();
  const done = (item?.acceptanceCriteria ?? card?.criteria ?? '').trim();
  const objective = goal?.description.trim() ?? '';
  const constraints = goal?.definition?.constraints.trim() ?? '';
  return [
    instruction,
    done === '' || done === instruction ? '' : `Done when: ${done}`,
    card?.uncertainty ? `Check first: ${card.uncertainty}` : '',
    goal === null ? '' : `My goal: ${goal.title}${objective === '' ? '' : ` — ${objective}`}`,
    constraints === '' ? '' : `Constraints: ${constraints}`,
  ].filter((part) => part !== '').join('\n\n');
}

/** Search results not already in the goal, at most a handful per query. */
export function addable(candidates: GoalItemDto[], goal: Pick<GoalDto, 'items'>, limit = 6): GoalItemDto[] {
  const present = new Set(goal.items.flatMap((item) => item.links).map(refKey));
  return candidates.filter((candidate) => !present.has(refKey(candidate))).slice(0, limit);
}

/** A pill's place in its page: its distance from the page's right and bottom edges. */
/** A side of the page the pill folds into. */
export type PillFold = 'left' | 'right';
/** Where the pill sits; folded, it is a tab on that side of the page at the same height. */
export type PillPosition = { right: number; bottom: number; fold?: PillFold };

type Box = { width: number; height: number };
type Edges = { left: number; top: number; right: number; bottom: number };

const PILL_MARGIN = 8;
const PILL_GAP = 10;
/** How close to a side of the page a drop folds the pill into it; a drop past the side does too. */
const FOLD_EDGE = 16;

export function foldSide(x: number, page: { left: number; right: number }): PillFold | null {
  if (x <= page.left + FOLD_EDGE) return 'left';
  return x >= page.right - FOLD_EDGE ? 'right' : null;
}

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
  // A folded tab sits on its side of the page, clear of a composer in the middle.
  if (saved?.fold !== undefined) return { ...placed, fold: saved.fold };
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

/**
 * The plan change the user can still accept: the newest answer's, as the differences it still makes to the goal's open work, one line each.
 * Once the work matches it, through Accept or the user's own changes, nothing is left to accept.
 */
export function openPlan(goal: GoalDto | null, exchanges: GoalExchangeDto[]): { exchangeId: string; reason: string; differences: string[] } | null {
  const last = exchanges.at(-1);
  if (goal === null || last === undefined || last.reply === null || last.reply.plan === null) return null;
  const plan = last.reply.plan;
  const items = new Map(goal.items.map((item) => [item.id, item]));
  const title = (id: string): string => items.get(id)?.title ?? id;
  const differences: string[] = [];
  for (const change of plan.changes) {
    const item = items.get(change.workItemId);
    const waitsOn = change.waitsOn;
    if (item === undefined) continue;
    if (change.priority !== null && change.priority !== item.priority) differences.push(`${item.title}: ${PRIORITY_WORDS[change.priority]} priority`);
    if (waitsOn !== null) {
      waitsOn.filter((id) => !item.blockedBy.includes(id)).forEach((id) => differences.push(`${item.title} waits on ${title(id)}`));
      item.blockedBy.filter((id) => !waitsOn.includes(id)).forEach((id) => differences.push(`${item.title} no longer waits on ${title(id)}`));
    }
    const criteria = change.acceptanceCriteria?.trim() ?? null;
    if (criteria !== null && criteria !== item.acceptanceCriteria) differences.push(`${item.title}: criteria become "${criteria}"`);
  }
  const order = plan.order;
  if (order !== null && order.join('\n') !== inOrder(goal.items).filter(isOpen).map((item) => item.id).join('\n')) {
    differences.push(`New order: ${order.map(title).join(' → ')}`);
  }
  return differences.length === 0 ? null : { exchangeId: last.id, reason: plan.reason, differences };
}

const PRIORITY_WORDS: Record<WorkItemPriority, string> = { HIGH: 'High', NORMAL: 'Normal', LOW: 'Low' };

/** What a started card is doing, as the panel names it. */
export const STARTED_LABELS: Record<GoalCardDto['kind'], string> = { RESEARCH: 'Researching', LESSON: 'Learning', WORK: 'Practicing', SPEC_CHECK: 'Checking',
  INVESTIGATE: 'Investigating' };

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
  const plan = openPlan(goal, exchanges);
  const unadded = gaps.filter((gap) => gap.workItemId === null);
  const decisions = (proposal === null ? 0 : 1) + (course === null ? 0 : 1) + (plan === null ? 0 : 1) + merged.length + unadded.length;
  return { text: situationText(goal, exchanges, { proposal: proposal !== null, course: course !== null, plan: plan !== null, merged, unadded: unadded.length, open }),
    decisions };
}

function situationText(goal: GoalDto, exchanges: GoalExchangeDto[], waiting: { proposal: boolean; course: boolean; plan: boolean; merged: WorkItemDto[]; unadded: number;
  open: WorkItemDto[]; }): string {
  const last = exchanges.at(-1);
  if (last?.state === 'PENDING') return 'Waiting for the agent';
  if (last?.state === 'FAILED') return 'The agent’s last reply failed; ask again';
  if (waiting.proposal) return 'Your decision: the proposed goal';
  if (waiting.course) return goal.course === null ? 'Your decision: the proposed course' : 'Your decision: the revised course';
  if (waiting.plan) return 'Your decision: the proposed plan change';
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

/**
 * What runs for the goal right now, a line each: its agent's reply, a research run or spec check a card started, and the running tasks of
 * its work. A started lesson, practice or investigation stays started while the user works on it, so only its task counts.
 */
export function goalRuns(goal: GoalDto, exchanges: GoalExchangeDto[]): string[] {
  const tasks = new Map(goal.items.flatMap((item) => item.links).filter((link) => link.kind === 'TASK' && link.state === 'WORKING')
    .map((link) => [link.key, link.title]));
  return [...(exchanges.at(-1)?.state === 'PENDING' ? ['Thinking…'] : []),
    ...goal.recommendations.filter((card) => card.state === 'STARTED' && (card.card.kind === 'RESEARCH' || card.card.kind === 'SPEC_CHECK'))
      .map((card) => `${STARTED_LABELS[card.card.kind]}: ${card.card.title}`),
    ...[...tasks.values()].map((title) => `Running: ${title}`)];
}

