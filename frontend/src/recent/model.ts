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
import type { RecentItemType, RecentReason, RecentRowDto } from '../types';

/** Fixed by the backend's retention sweep; the calendar greys everything older. */
export const RETENTION_DAYS = 14;

export type RangeKey = 'today' | 'yesterday' | 'last7' | 'last14';

export const RANGE_OPTIONS: Array<{ key: RangeKey; label: string; days: number; offset: number }> = [
  { key: 'today', label: 'Today', days: 1, offset: 0 },
  { key: 'yesterday', label: 'Yesterday', days: 1, offset: 1 },
  { key: 'last7', label: 'Last 7 days', days: 7, offset: 0 },
  { key: 'last14', label: 'Last 14 days', days: RETENTION_DAYS, offset: 0 },
];

export const TYPE_GLYPH: Record<RecentItemType, string> = {
  PULL_REQUEST: '⑃',
  ISSUE: '◉',
  TASK: '☑',
  COMMIT: '⑂',
};

export function isoDay(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

export function parseDay(iso: string): Date {
  const [year, month, day] = iso.split('-').map(Number);
  return new Date(year, month - 1, day);
}

export function shiftDay(iso: string, days: number): string {
  const date = parseDay(iso);
  date.setDate(date.getDate() + days);
  return isoDay(date);
}

/** The window a range option selects, as inclusive ISO days. */
export function rangeWindow(key: RangeKey, today: string): { from: string; to: string } {
  const option = RANGE_OPTIONS.find(candidate => candidate.key === key) ?? RANGE_OPTIONS[3];
  const to = shiftDay(today, -option.offset);
  return { from: shiftDay(to, -(option.days - 1)), to };
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

export function shortDate(iso: string): string {
  const date = parseDay(iso);
  return `${MONTHS[date.getMonth()]} ${date.getDate()}`;
}

export function rangeLabel(from: string, to: string): string {
  return from === to ? shortDate(from) : `${shortDate(from)} – ${shortDate(to)}`;
}

/**
 * Day-group heading. The two most recent days get a word rather than a date,
 * because that is how the day is actually remembered.
 */
export function dayHeading(iso: string, today: string): string {
  if (iso === today) return 'TODAY';
  if (iso === shiftDay(today, -1)) return 'YESTERDAY';
  const date = parseDay(iso);
  const within = Math.round((parseDay(today).getTime() - date.getTime()) / 86_400_000);
  if (within < 7) return `${WEEKDAYS[date.getDay()]}, ${MONTHS[date.getMonth()]} ${date.getDate()}`.toUpperCase();
  return `${MONTHS[date.getMonth()]} ${date.getDate()}, ${date.getFullYear()}`.toUpperCase();
}

export function clockTime(iso: string): string {
  const date = new Date(iso);
  const hours = date.getHours();
  const minutes = `${date.getMinutes()}`.padStart(2, '0');
  const suffix = hours < 12 ? 'AM' : 'PM';
  return `${hours % 12 === 0 ? 12 : hours % 12}:${minutes} ${suffix}`;
}

/** Badge colour. Verdicts read green, discussion purple, agent work amber. */
export function reasonTone(reason: RecentReason): 'green' | 'purple' | 'amber' | 'neutral' {
  switch (reason) {
    case 'APPROVED':
    case 'PUSHED':
      return 'green';
    case 'COMMENTED':
    case 'CHANGES_REQUESTED':
    case 'MERGED':
      return 'purple';
    case 'PROMPTED':
      return 'amber';
    default:
      return 'neutral';
  }
}

/** Glyph colour carries role: purple is mine, green is theirs. */
export function roleTone(row: Pick<RecentRowDto, 'type' | 'mine'>): 'mine' | 'theirs' | 'own' {
  if (row.type === 'COMMIT') return 'own';
  if (row.type === 'TASK') return 'mine';
  return row.mine ? 'mine' : 'theirs';
}

export type HighlightPart = { text: string; match: boolean };

/** Splits a sentence on the backend's match offsets so the renderer never re-finds the term. */
export function highlightParts(
  sentence: string,
  ranges: Array<{ start: number; end: number }>,
): HighlightPart[] {
  const parts: HighlightPart[] = [];
  let cursor = 0;
  for (const range of ranges) {
    const start = Math.max(range.start, cursor);
    const end = Math.min(range.end, sentence.length);
    if (end <= start) continue;
    if (start > cursor) parts.push({ text: sentence.slice(cursor, start), match: false });
    parts.push({ text: sentence.slice(start, end), match: true });
    cursor = end;
  }
  if (cursor < sentence.length) parts.push({ text: sentence.slice(cursor), match: false });
  return parts;
}

/** GitHub-flavoured link and Markdown forms, the two things worth copying off a row. */
export function rowLink(row: RecentRowDto): string {
  return row.htmlUrl ?? '';
}

export function rowMarkdown(row: RecentRowDto): string {
  const number = row.number === null ? '' : ` #${row.number}`;
  return row.htmlUrl ? `[${row.title}${number}](${row.htmlUrl})` : `${row.title}${number}`;
}

export type CalendarCell = { iso: string; day: number; active: boolean; inWindow: boolean };

/** One month of squares. Days outside the kept window cannot be picked, which is how retention explains itself. */
export function calendarCells(
  month: string,
  activeDays: string[],
  windowStart: string,
  today: string,
): Array<CalendarCell | null> {
  const first = parseDay(month);
  first.setDate(1);
  const cells: Array<CalendarCell | null> = new Array(first.getDay()).fill(null);
  const active = new Set(activeDays);
  const daysInMonth = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  for (let day = 1; day <= daysInMonth; day++) {
    const iso = isoDay(new Date(first.getFullYear(), first.getMonth(), day));
    cells.push({
      iso,
      day,
      active: active.has(iso),
      inWindow: iso >= windowStart && iso <= today,
    });
  }
  return cells;
}

/**
 * Whether a stream fetch may start. Paging is dropped while a request is
 * already out, or scrolling would stack loads and race past the day being
 * read; a filter change never is, or a chip toggled while the previous one is
 * still in flight ends up lit with the old rows still on screen.
 */
export function mayLoad(reset: boolean, inFlight: boolean): boolean {
  return reset || !inFlight;
}

/**
 * Turns a failed stream fetch into something a person can act on. Electron
 * wraps every IPC rejection as "Error invoking remote method '<channel>': …",
 * which says nothing about what went wrong or what to do next.
 */
export function streamErrorMessage(failure: unknown): string {
  const raw = failure instanceof Error ? failure.message : String(failure);
  const unwrapped = raw.replace(/^Error invoking remote method '[^']*':\s*/, '');
  if (/fetch failed|ECONNREFUSED|ECONNRESET|socket hang up/i.test(unwrapped)) {
    return 'The ByteQuay backend is not answering. Recent is stored on this Mac, '
      + 'so nothing is lost — the stream fills back in once the backend is running.';
  }
  return unwrapped || 'Recent could not be loaded.';
}

/** Splits an `owner/name` repository into its parts, or null when there isn't one. */
export function splitRepo(repo: string | null | undefined): { owner: string; name: string } | null {
  const slash = repo ? repo.indexOf('/') : -1;
  return repo && slash > 0 ? { owner: repo.slice(0, slash), name: repo.slice(slash + 1) } : null;
}
