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
import type { CherryPickExecution, CherryPickExecutionItem, GitCommitDto } from '../types';

/**
 * Selection is always one contiguous span: the checked SHAs only anchor it,
 * and everything between the outermost anchors is effectively selected.
 * `commits` is newest-first, exactly as `git log` returns it.
 */
export function selectedSpan(commits: GitCommitDto[], checked: ReadonlySet<string>): {
  lo: number;
  hi: number;
} | null {
  let lo = -1;
  let hi = -1;
  commits.forEach((commit, index) => {
    if (!checked.has(commit.sha)) return;
    if (lo < 0 || index < lo) lo = index;
    if (index > hi) hi = index;
  });
  return lo < 0 ? null : { lo, hi };
}

export function inSpan(index: number, span: { lo: number; hi: number } | null): boolean {
  return span !== null && index >= span.lo && index <= span.hi;
}

/**
 * Typed from/to SHA prefixes select the same span checkboxes would.
 * Order does not matter; either input may name the newer commit.
 */
export function spanFromShas(
  commits: GitCommitDto[],
  from: string,
  to: string,
): { anchors: [string, string] } | { error: true } {
  const find = (prefix: string) => {
    const needle = prefix.trim().toLowerCase();
    if (needle === '') return -1;
    return commits.findIndex(commit => commit.sha.toLowerCase().startsWith(needle));
  };
  const first = find(from);
  const second = find(to);
  if (first < 0 || second < 0) return { error: true };
  return { anchors: [commits[first].sha, commits[second].sha] };
}

export type DayGroup = { label: string; commits: Array<{ commit: GitCommitDto; index: number }> };

const DAY_FORMAT = new Intl.DateTimeFormat('en-US', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
});

export function groupByDay(commits: GitCommitDto[]): DayGroup[] {
  const groups: DayGroup[] = [];
  let currentKey = '';
  commits.forEach((commit, index) => {
    const date = new Date(commit.authoredAt);
    const key = Number.isNaN(date.getTime()) ? 'Undated' : DAY_FORMAT.format(date);
    if (key !== currentKey) {
      currentKey = key;
      groups.push({ label: `Commits on ${key}`, commits: [] });
    }
    groups[groups.length - 1].commits.push({ commit, index });
  });
  return groups;
}

/** Default combined message for squashing a span (oldest → newest). */
export function squashMessage(spanNewestFirst: GitCommitDto[]): string {
  if (spanNewestFirst.length === 0) return '';
  const newest = spanNewestFirst[0];
  const rest = spanNewestFirst.slice(1);
  return `${newest.title}\n\n${rest.map(commit => `* ${commit.title}`).join('\n')}`
    + `\n\nSquashed: ${[...spanNewestFirst].reverse().map(commit => commit.sha.slice(0, 7)).join(', ')}`;
}

export type TaskState = 'running' | 'paused' | 'done' | 'canceled';

export function taskState(execution: CherryPickExecution): TaskState {
  if (execution.status === 'RUNNING') return 'running';
  if (execution.status === 'PAUSED') return 'paused';
  return execution.finishReason === 'CANCELLED' ? 'canceled' : 'done';
}

export function isOpenTask(execution: CherryPickExecution): boolean {
  const state = taskState(execution);
  return state === 'running' || state === 'paused';
}

/** Running and paused tasks always sort first; order is otherwise preserved. */
export function orderTasks(executions: CherryPickExecution[]): CherryPickExecution[] {
  return [...executions.filter(isOpenTask), ...executions.filter(task => !isOpenTask(task))];
}

export type TaskFilter = 'all' | 'open' | 'done';

export function filterTasks(executions: CherryPickExecution[], filter: TaskFilter): CherryPickExecution[] {
  if (filter === 'all') return executions;
  return executions.filter(task => (filter === 'open' ? isOpenTask(task) : !isOpenTask(task)));
}

export type NavRow<T> =
  | { kind: 'item'; value: T }
  | { kind: 'fold'; label: string; expand: boolean };

/**
 * Folds a long item list around the active index: the previous item, the
 * current one, and the next seven stay visible; both tails collapse into
 * summary rows.
 */
export function foldNav<T>(items: T[], activeIndex: number, expanded: boolean): Array<NavRow<T>> {
  const active = activeIndex < 0 ? items.length - 1 : activeIndex;
  const start = Math.max(0, active - 1);
  const end = Math.min(items.length - 1, active + 7);
  if (expanded || (start === 0 && end === items.length - 1)) {
    const rows: Array<NavRow<T>> = items.map(value => ({ kind: 'item' as const, value }));
    if (start > 0 || end < items.length - 1) {
      rows.unshift({ kind: 'fold', label: 'Collapse to the active window', expand: false });
    }
    return rows;
  }
  const rows: Array<NavRow<T>> = [];
  if (start > 0) rows.push({ kind: 'fold', label: `${start} earlier items — settled`, expand: true });
  for (let index = start; index <= end; index++) rows.push({ kind: 'item', value: items[index] });
  if (end < items.length - 1) {
    rows.push({ kind: 'fold', label: `${items.length - 1 - end} more pending`, expand: true });
  }
  return rows;
}

export function shortSha(sha: string): string {
  return sha.slice(0, 7);
}

export function completedExecutionItems(items: CherryPickExecutionItem[]): number {
  return items.filter(item => item.result !== 'PENDING').length;
}
