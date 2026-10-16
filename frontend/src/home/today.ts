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
import type { AgendaDto, AgendaItemDto, AgendaLinkDto, AgendaTargetDto } from '../types';

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

/** The keys of today's open items, for the sun beside a Needs attention row. */
export function todayKeys(agenda: AgendaDto | null): ReadonlySet<string> {
  if (agenda === null) return new Set();
  return new Set(agenda.items.filter(item => item.status === 'open').map(item => attentionKey(item.target)).filter((key): key is string => key !== null));
}

/** What closed a done row, with its local time when known: "Merged 00:14". */
export function doneText(item: AgendaItemDto): string {
  const text = item.doneText ?? 'Done';
  return item.doneAt === null ? text : `${text} ${clockTime(item.doneAt)}`;
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
