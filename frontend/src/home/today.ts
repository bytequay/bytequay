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
import { plural } from '@bytequay/ui/displayText';
import { taskCardState } from '../agents/attention';
import type { AgendaAction, AgendaCalendarDto, AgendaDto, AgendaItemDto, AgendaLinkDto, AgendaTargetDto } from '../types';
import { statusPill, type AttentionItem, type Handled } from './attention';

export type TodayScope = 'global' | 'workspace';

/** Open rows in the agent's order, then done rows; rows set aside for today are hidden. */
export function todayRows(items: AgendaItemDto[]): AgendaItemDto[] {
  return [...items.filter(item => item.status === 'open'), ...items.filter(item => item.status === 'done')];
}

/** Done over everything not set aside. */
export function doneRatio(items: AgendaItemDto[]): { done: number; total: number } {
  const shown = items.filter(item => item.status !== 'dismissed');
  return { done: shown.filter(item => item.status === 'done').length, total: shown.length };
}

export function hiddenCount(items: AgendaItemDto[]): number {
  return items.filter(item => item.status === 'dismissed').length;
}

export function allDone(items: AgendaItemDto[]): boolean {
  const { done, total } = doneRatio(items);
  return total > 0 && done === total;
}

/**
 * Whether a Home draws the section at all: a workspace Home only once it has items of its own, and nothing while the
 * agenda is off.
 */
export function showsToday(agenda: AgendaDto | null, scope: TodayScope): agenda is AgendaDto {
  if (agenda === null || !agenda.enabled && agenda.state === 'NONE') return false;
  return scope === 'global' || agenda.items.length > 0;
}

/** Where an item lives, as its row names it: a workspace Home drops the repository it already is. */
export function whereText(target: AgendaTargetDto, scope: TodayScope, where: string | null = null): string {
  if (where !== null) return scope === 'workspace' ? where.replace(/^[\w.-]+\/[\w.-]+ (?=#\d+$)/, '') : where;
  switch (target.kind) {
    case 'PULL':
    case 'ISSUE':
      return scope === 'workspace' || target.repo === null ? `#${target.number ?? ''}` : `${target.repo} #${target.number ?? ''}`;
    case 'JIRA':
      return 'Jira';
    case 'TASK':
      return 'Task';
    case 'REVIEW':
      return 'Review';
    case 'WORK':
      return 'Work item';
  }
}

/** A row's reference in the compact form of a key: "repository#42" on Home, "#42" on a workspace Home, else what whereText says. */
export function todayRef(target: AgendaTargetDto, scope: TodayScope, where: string | null = null): string {
  return whereText(target, scope, where).replace(/^[\w.-]+\/([\w.-]+) #(\d+)$/, '$1#$2');
}

/** A reason chip's tint: red blocks or is overdue, amber waits, blue is news, green resolved, grey neutral. */
export type TodayChip = { text: string; tone: 'red' | 'amber' | 'blue' | 'green' | 'neutral' };

const CHIP_ORDER: TodayChip['tone'][] = ['red', 'amber', 'blue', 'green', 'neutral'];

const ACTION_CHIPS: Record<AgendaAction, string> = {
  start: 'Start', reply: 'Reply', review: 'Review', fix: 'Fix', merge: 'Merge', answer: 'Answer', decide: 'Decide', follow_up: 'Follow up',
};

/**
 * Up to three chips for why a row is on Today, from what the Homes already hold about its item; the model's sentence waits for the row
 * to open. A row with no such facts names its action, and a done row what closed it.
 */
export function todayChips(item: AgendaItemDto, fact: AttentionItem | undefined, now: number): TodayChip[] {
  if (item.status === 'done') return [{ text: doneText(item), tone: 'green' }];
  const chips: TodayChip[] = [];
  const days = item.at === null ? 0 : Math.floor((now - Date.parse(item.at)) / 86_400_000);
  if (days >= 1) chips.push({ text: `Waiting ${days}d`, tone: days >= 7 ? 'red' : 'amber' });
  const pr = fact?.pr ?? null;
  const state = pr?.state?.toLowerCase();
  if (pr !== null && (state === 'merged' || state === 'closed')) chips.push({ text: `PR ${state}`, tone: state === 'merged' ? 'green' : 'neutral' });
  else if (pr !== null) {
    if (pr.ciStatus === 'FAILING') chips.push({ text: 'CI failing', tone: 'red' });
    if (pr.reviewDecision === 'CHANGES_REQUESTED') chips.push({ text: 'Changes requested', tone: 'red' });
    if (pr.mergeableState === 'dirty') chips.push({ text: 'Merge conflicts', tone: 'red' });
    else if (pr.mergeableState === 'blocked') chips.push({ text: 'Merge blocked', tone: 'red' });
    else if (pr.mergeable === true && pr.mergeableState === 'clean' && pr.reviewDecision === 'APPROVED') chips.push({ text: 'Ready to merge', tone: 'green' });
    const event = pr.latestActivity?.event;
    if (event === 'commented' || event === 'review_thread') chips.push({ text: 'New comment', tone: 'blue' });
    const commits = pr.commitsSinceReview?.length ?? 0;
    if (commits > 0) chips.push({ text: `+${plural(commits, 'commit')}`, tone: 'neutral' });
  }
  const task = fact?.task;
  if (task !== undefined) {
    const card = taskCardState(task.dto, task.activity);
    if (card.label === 'Needs you') chips.push({ text: card.reason === 'Question for you' ? 'Question for you' : 'Agent waiting', tone: 'amber' });
    else if (card.spinning) chips.push({ text: 'Agent running', tone: 'blue' });
    else if (card.label === 'Failed') chips.push({ text: 'Task failed', tone: 'red' });
    if (task.dto.pullRequest?.state === 'merged') chips.push({ text: 'PR merged', tone: 'green' });
  }
  if (fact?.jira !== undefined) {
    const { tone } = statusPill(fact.jira.status);
    chips.push({ text: fact.jira.status.name, tone: tone === 'red' || tone === 'blue' || tone === 'green' ? tone : 'neutral' });
  }
  if (fact?.issueState === 'closed') chips.push({ text: 'Closed', tone: 'green' });
  if (chips.length === 0) chips.push({ text: ACTION_CHIPS[item.action], tone: 'neutral' });
  return chips.map((chip, index) => ({ chip, index }))
    .sort((left, right) => CHIP_ORDER.indexOf(left.chip.tone) - CHIP_ORDER.indexOf(right.chip.tone) || left.index - right.index)
    .slice(0, 3).map(entry => entry.chip);
}

/**
 * The Needs attention key of the thing an item opens, so that list can mark what is on Today. Pull requests and issues
 * share the repository#number form, which compares in lower case.
 */
export function attentionKey(target: AgendaTargetDto): string | null {
  switch (target.kind) {
    case 'PULL':
    case 'ISSUE':
      return target.repo === null || target.number === null ? null : `${target.repo}#${target.number}`.toLowerCase();
    case 'TASK':
      return target.id === null ? null : `task:${target.id}`;
    case 'JIRA':
      return target.cloudId === null || target.id === null ? null : `jira:${target.cloudId}:${target.id}`;
    default:
      return null;
  }
}

/** A snoozed item leaves Today until its snooze ends, whatever happens to it meanwhile, as its Needs attention card does. */
export function withoutSnoozed(agenda: AgendaDto, handled: Handled, now: number): AgendaDto {
  const snoozed = new Set(Object.entries(handled).filter(([, entry]) => entry.kind === 'snooze' && entry.until !== null && now < entry.until)
    .map(([key]) => key.toLowerCase()));
  return snoozed.size === 0 ? agenda : { ...agenda, items: agenda.items.filter(item => !snoozed.has(attentionKey(item.target)?.toLowerCase() ?? '')) };
}

/** The keys of today's open items, for the Today tag beside a My work card or Needs attention row. */
export function todayKeys(agenda: AgendaDto | null): ReadonlySet<string> {
  if (agenda === null) return new Set();
  return new Set(agenda.items.filter(item => item.status === 'open').map(item => attentionKey(item.target)).filter((key): key is string => key !== null));
}

/** What closed a done row, with its local time when known: "Merged 00:14". */
export function doneText(item: AgendaItemDto): string {
  const text = item.doneText ?? 'Done';
  return item.doneAt === null ? text : `${text} ${clockTime(item.doneAt)}`;
}

/** The header's calendar: hours booked with the free time on hover, a dimmed clock when it was not read, nothing when it is off. */
export function calendarFact(calendar: AgendaCalendarDto | null): { text: string; title: string; dim: boolean } | null {
  if (calendar === null) return null;
  if (calendar.state === 'unavailable') return { text: '', title: 'Calendar not checked', dim: true };
  if (calendar.state !== 'used') return null;
  const free = calendar.free.map(block => `${block.from}–${block.to}`).join(', ');
  const title = [calendar.allDay ? 'All-day event today' : null, free === '' ? 'No free time between 9:00 and 18:00' : `Free ${free}`]
    .filter(part => part !== null).join(' · ');
  return { text: `${calendar.busyHours} h`, title, dim: false };
}

/** A local 24-hour time, as the header and done rows show it. */
export function clockTime(value: string | number): string {
  const date = new Date(value);
  return `${date.getHours()}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** The schedule's time of day, "9:00". */
export function minuteText(minute: number): string {
  return `${Math.floor(minute / 60)}:${String(minute % 60).padStart(2, '0')}`;
}

/** The header's date, "Fri 16 Oct". */
export function dayText(day: string): string {
  const [year, month, date] = day.split('-').map(Number);
  const value = new Date(year, month - 1, date);
  return `${value.toLocaleDateString('en-GB', { weekday: 'short' })} ${date} ${value.toLocaleDateString('en-GB', { month: 'short' })}`;
}

/** A recap chip's name: the number alone in the repository most chips share, else the repository's own name too. */
export function recapLabel(link: AgendaLinkDto, links: AgendaLinkDto[]): string {
  const { target } = link;
  if (target.kind !== 'PULL' && target.kind !== 'ISSUE' || target.repo === null) return whereText(target, 'global');
  const counts = new Map<string, number>();
  links.forEach(other => { if (other.target.repo !== null) counts.set(other.target.repo, (counts.get(other.target.repo) ?? 0) + 1); });
  const main = [...counts.entries()].sort((left, right) => right[1] - left[1])[0]?.[0];
  return target.repo === main ? `#${target.number ?? ''}` : `${target.repo.slice(target.repo.indexOf('/') + 1)} #${target.number ?? ''}`;
}
