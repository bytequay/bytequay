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
import type { AppPage } from '../AppSidebar';
import type { RunningTask } from '../agents/runningTaskModel';
import { pullRequestFromRoute, type ZoomedReference } from '../githubReference';
import { dayText } from '../home/today';
import { needsAction, type AppNotice } from '../notices/contracts';
import { SETTINGS_ENTRIES, isWorkspaceSection, settingsRoute } from '../settings/routes';
import type { AgendaTargetDto, AssistantItemDto, AssistantRowDto } from '../types';
import { workspaceLocationFromRoute } from '../workspaces/model';

/** The pills over the stack, in the order Tab moves through them. */
export const GROUPS = ['all', 'decision', 'running', 'finished', 'today'] as const;
export type Group = typeof GROUPS[number];

/** One card in the stack: a task's notice, or a task still running. */
export type WaitingCard = { key: string; group: 'decision' | 'finished'; at: number; notice: AppNotice }
  | { key: string; group: 'running'; at: number; task: RunningTask };

/** Newest first: notices that need the user are Decisions, finished turns are Finished, and running tasks are Running. */
export function waitingCards(notices: AppNotice[], tasks: RunningTask[]): WaitingCard[] {
  const cards: WaitingCard[] = notices.map(notice => ({ key: notice.id, group: needsAction(notice) ? 'decision' : 'finished', at: Date.parse(notice.occurredAt), notice }));
  for (const task of tasks) {
    if (task.status === 'RUNNING') cards.push({ key: `running:${task.id}`, group: 'running', at: Date.parse(task.startedAt), task });
  }
  return cards.sort((left, right) => right.at - left.at);
}

const REPLY_WORDS = 50;

/** A reply as a card quotes it: its first 50 words, on one line. */
export function replyExcerpt(text: string): string {
  const words = text.trim().split(/\s+/);
  return words.length > REPLY_WORDS ? `${words.slice(0, REPLY_WORDS).join(' ')}…` : words.join(' ');
}

/** The top card and the two peeking behind it, wrapping round so paging never runs out of peeks. */
export function stackOf<T>(cards: T[], top: number): T[] {
  return Array.from({ length: Math.min(3, cards.length) }, (_, offset) => cards[(top + offset) % cards.length]);
}

/**
 * The ref of what the user is looking at, so "this one" in a message can resolve: the top layer first, then the page's route. Pages whose
 * open item lives outside the route send none.
 */
export function screenRef(layer: ZoomedReference | undefined, hash: string, workspaceRepo: string | null): string | null {
  if (layer !== undefined) {
    switch (layer.kind) {
      case 'pull': return `pr:${layer.repo.toLowerCase()}#${layer.number}`;
      case 'issue': return `issue:${layer.repo.toLowerCase()}#${layer.number}`;
      case 'jira': return `jira:${layer.cloudId}:${layer.issueId}`;
      case 'work': return `work:${layer.itemId}`;
      case 'task': return `task:${layer.taskId}`;
    }
  }
  const pull = pullRequestFromRoute(hash);
  if (pull !== null) return `pr:${pull.repo.toLowerCase()}#${pull.number}`;
  const location = workspaceLocationFromRoute(hash);
  if (location === null) return null;
  if (location.reviewId !== undefined) return `review:${location.reviewId}`;
  if (location.agentTaskId !== null) return `task:${location.agentTaskId}`;
  if (workspaceRepo === null) return null;
  if (location.pullRequestNumber !== null) return `pr:${workspaceRepo.toLowerCase()}#${location.pullRequestNumber}`;
  if (location.issueNumber !== null) return `issue:${workspaceRepo.toLowerCase()}#${location.issueNumber}`;
  return null;
}

const KIND_NAMES: Record<string, string> = { jira: 'Jira ticket', work: 'work item', epic: 'Epic', task: 'task', review: 'review' };

/** A ref's short name, as the On screen chip and an unlabelled reply link show it. */
export function refLabel(ref: string): string {
  const number = /^(?:pr|issue):[^#]+#(\d+)$/.exec(ref);
  return number !== null ? `#${number[1]}` : KIND_NAMES[ref.slice(0, ref.indexOf(':'))] ?? ref;
}

// The agent renderer opens only web links, so a ref travels as one that the panel catches before anything opens it.
const REF_LINK = 'https://bytequay.invalid/ref?r=';

export function refFromHref(href: string): string | null {
  return href.startsWith(REF_LINK) ? decodeURIComponent(href.slice(REF_LINK.length)) : null;
}

const escape = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Turns the refs a reply names into links where it has a target, written as `[label](ref)` or bare; a ref link without one keeps
 * only its label, since nothing could open it.
 */
export function linkRefs(text: string, targets: Record<string, AgendaTargetDto>): string {
  const known = Object.keys(targets).sort((a, b) => b.length - a.length).map(escape);
  const bare = known.length === 0 ? '' : `|(?<![\\w:/#\`])(${known.join('|')})(?![\\w-])`;
  const pattern = new RegExp(`\\[([^\\]\\n]*)\\]\\(((?:pr|issue|jira|task|review|work|epic):[^)\\s]+)\\)${bare}`, 'g');
  return text.replace(pattern, (whole: string, label: string | undefined, linked: string | undefined, named: string | undefined) => {
    const ref = linked ?? named;
    if (ref === undefined) return whole;
    if (!(ref in targets)) return label ?? whole;
    return `[${label ?? refLabel(ref)}](${REF_LINK}${encodeURIComponent(ref)})`;
  });
}

/** The local day an instant falls on, as yyyy-mm-dd. */
export function localDay(at: string | number): string {
  const date = new Date(at);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** A timeline day's header: Today, Yesterday, or its date. */
export function dayLabel(day: string, now: number = Date.now()): string {
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  return day === localDay(now) ? 'Today' : day === localDay(yesterday.getTime()) ? 'Yesterday' : dayText(day);
}

export type TimelineDay = { day: string | null; refs: string[] };

/** A list as a timeline: each ref under the local day it last moved, the newest first, then the refs with no time, gone ones too. */
export function timelineDays(refs: string[], rows: Record<string, AssistantRowDto>): TimelineDay[] {
  const at = (ref: string): number | null => {
    const row = rows[ref];
    return row === undefined || row.at === null ? null : Date.parse(row.at);
  };
  const days: TimelineDay[] = [];
  for (const ref of refs.filter(each => at(each) !== null).sort((left, right) => (at(right) ?? 0) - (at(left) ?? 0))) {
    const day = localDay(at(ref) ?? 0);
    const last = days.at(-1);
    if (last?.day === day) last.refs.push(ref);
    else days.push({ day, refs: [ref] });
  }
  const untimed = refs.filter(ref => at(ref) === null);
  return untimed.length === 0 ? days : [...days, { day: null, refs: untimed }];
}

const capital = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1).replaceAll('_', ' ');

/** Where a row stands, as a card's strip names it: what needs doing on a pull request first, else its own state. */
export function rowState(row: AssistantRowDto): string | null {
  if (row.kind === 'PULL' && row.state === 'open') {
    if (row.review === 'changes_requested') return 'Changes requested';
    if (row.ci === 'failing') return 'Checks failing';
    if (row.mergeBlocked) return 'Merge blocked';
    if (row.review === 'approved') return 'Approved';
  }
  return row.state === null ? null : capital(row.state);
}

/** What a row's or a card's tooltip says it is: a pull request's or issue's repository and number, or else its kind or agent. */
export function rowTitle(row: AssistantRowDto): string {
  switch (row.kind) {
    case 'PULL':
    case 'ISSUE': return row.target.repo === null || row.key === null ? row.title : `${row.target.repo} ${row.key} · ${row.title}`;
    case 'JIRA': return row.key === null ? row.title : `Jira ${row.key} · ${row.title}`;
    case 'TASK':
    case 'REVIEW': return row.agent === null ? row.title : `${row.agent} · ${row.title}`;
    case 'WORK': return `Work item · ${row.title}`;
    case 'EPIC': return `Epic · ${row.title}`;
  }
}

// The pages the assistant may open: the sidebar's, and each Settings section outside a workspace. AssistantShow.java holds the twin.
const PAGES: readonly AppPage[] = ['home', 'workspaces', 'teams', 'recent', 'pulls', 'jira', 'google', 'settings'];

/** Where a page the assistant opened lives: `settings/models` is a Settings section, and the rest are the sidebar's pages. */
export function pageRoute(page: string): string | null {
  const entry = SETTINGS_ENTRIES.find(each => `settings/${each.section}` === page);
  if (entry !== undefined) return isWorkspaceSection(entry.section) ? null : settingsRoute(entry.section);
  return PAGES.some(each => each === page) ? `#/${page}` : null;
}

/**
 * The open the panel follows by itself: the newest done OPEN receipt that came in after the panel opened and was not followed yet.
 * One read later only offers its Open.
 */
export function arrivedOpen(items: AssistantItemDto[], openedAt: number, followed: ReadonlySet<string>): AssistantItemDto | null {
  return items.findLast(item => item.kind === 'RECEIPT' && item.receipt?.operation === 'OPEN' && item.receipt.outcome === 'DONE'
    && Date.parse(item.at) > openedAt && !followed.has(item.id)) ?? null;
}

/** The work items of the day's drafts that the user created, named by the receipts their clicks left. */
export function createdDrafts(items: AssistantItemDto[]): ReadonlySet<string> {
  return new Set(items.flatMap(item => item.receipt?.operation === 'CREATE_EPIC' && item.receipt.target?.id != null ? [item.receipt.target.id] : []));
}
